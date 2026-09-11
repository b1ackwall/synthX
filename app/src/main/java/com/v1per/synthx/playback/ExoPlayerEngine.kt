package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * [PlaybackEngine] implementation backed by a media3 [MediaController]/[SynthXPlaybackService].
 *
 * Split across several files for length, all part of the same class: the [Player.Listener]
 * lives in `ExoPlayerEngineListener.kt`, queue-state derivation (`refreshQueue` and friends)
 * in `ExoPlayerEngineQueueState.kt`, artwork loading in `ExoPlayerEngineArtwork.kt`, and the
 * real bodies of the queue-mutation overrides in `ExoPlayerEngineQueueOps.kt` (this file keeps
 * only their one-line delegators, since Kotlin requires `override` members to be declared in
 * the class body itself). Fields those files need to reach are `internal` rather than
 * `private` for that reason — still invisible outside the module, not part of the public
 * [PlaybackEngine] contract.
 */
class ExoPlayerEngine(
    context: Context,
    internal val persistence: QueuePersistence,
) : PlaybackEngine {

    // Needed to resolve album-art content:// URIs through the ContentResolver
    internal val appContext = context.applicationContext

    internal val _isPlaying = MutableStateFlow(false)
    override val isPlaying: Flow<Boolean> = _isPlaying.asStateFlow()

    internal val _isBuffering = MutableStateFlow(false)
    override val isBuffering: Flow<Boolean> = _isBuffering.asStateFlow()

    internal val _currentSongId = MutableStateFlow<String?>(null)
    override val currentSongId: Flow<String?> = _currentSongId.asStateFlow()

    internal val _currentSong = MutableStateFlow<Song?>(null)
    override val currentSong: Flow<Song?> = _currentSong.asStateFlow()

    /** Media3 identity of the exact playing queue occurrence; never exposed as song identity. */
    internal val _currentSlotId = MutableStateFlow<String?>(null)

    // The full live order, hydrated for consumers. Recomputed only when the order store
    // itself changes — a plain track transition moves the cursor, not the order, so it
    // refreshes _currentIndex alone rather than re-projecting the whole list.
    internal val _queue = MutableStateFlow<List<QueueSlot>>(emptyList())
    override val queue: Flow<List<QueueSlot>> = _queue.asStateFlow()

    internal val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: Flow<Int> = _currentIndex.asStateFlow()

    internal val _upcomingQueue = MutableStateFlow<List<QueueSlot>>(emptyList())
    override val upcomingQueue: Flow<List<QueueSlot>> = _upcomingQueue.asStateFlow()

    internal val _shuffleEnabled = MutableStateFlow(false)
    override val shuffleEnabled: Flow<Boolean> = _shuffleEnabled.asStateFlow()

    /**
     * Owns the full intended play order — both the live/active order and the unshuffled
     * baseline (see [QueueOrderStore]'s own kdoc). This, not the player's timeline, is what
     * "the queue" means: the timeline holds only a bounded window into it, reconciled by
     * [maintainWindow]. [_queue] is this store hydrated to real songs.
     */
    internal val orderStore = QueueOrderStore(persistence)

    internal val _repeatMode = MutableStateFlow(RepeatMode.Off)
    override val repeatMode: Flow<RepeatMode> = _repeatMode.asStateFlow()

    internal val _isQueueCustomized = MutableStateFlow(false)
    override val isQueueCustomized: Flow<Boolean> = _isQueueCustomized.asStateFlow()

    // Recomputed imperatively (see refreshNavigationState) rather than derived from other
    // flows: it is a repeat-aware question about the order store's true length and position,
    // and neither the store nor the cursor is a Flow. Deliberately NOT ctrl.hasNextMediaItem()
    // any more — the loaded timeline ends ~WINDOW_FORWARD ahead of the current song, so the
    // controller would answer "no next item" with hundreds of songs still queued.
    internal val _hasNext = MutableStateFlow(false)
    override val hasNext: Flow<Boolean> = _hasNext.asStateFlow()

    // Pure function of currentSongId: whenever a current item exists, previous() always
    // does something (restart, or jump — see previous()'s kdoc), so this needs no
    // separate refresh hook.
    override val hasPrevious: Flow<Boolean> = currentSongId.map { it != null }

    // replay = 0 with a 1-slot buffer: a failure that lands while nothing is collecting
    // is dropped rather than replayed later as a stale message.
    internal val _errors = MutableSharedFlow<PlaybackError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val errors: Flow<PlaybackError> = _errors.asSharedFlow()

    // MediaItem.mediaId holds QueueSlot.slotId, while this map retains the corresponding Song.
    // Holds a Song for every slot in the order store, not just the loaded window: the Up Next
    // list shows the whole queue, and window reconciliation needs to be able to build a
    // MediaItem for any slot that scrolls into range. Written and read on the main thread only
    // (playback mutations and Player.Listener callbacks both run there), so it needs no
    // synchronisation.
    internal val songsBySlotId = LinkedHashMap<String, Song>()

    // Slot ids whose MediaItem in the loaded window already carries artwork bytes.
    internal val artworkLoadedSlotIds = mutableSetOf<String>()

    // Main.immediate to match MediaController's main-thread requirement; the artwork
    // work itself hops to IO/Default and comes back.
    internal val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var artworkJob: Job? = null

    // Single 500ms ticker shared by currentPosition and duration below — previously each
    // ran its own independent delay loop polling the same controller snapshot twice per
    // tick for no reason. shareIn (rather than two separate cold flows) means only one
    // timer runs regardless of how many downstream collectors currentPosition/duration
    // pick up. Collected on the caller's dispatcher (the ViewModel collects on
    // Main.immediate via stateIn), which satisfies MediaController's main-thread requirement.
    private val positionTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(500)
        }
    }.shareIn(engineScope, SharingStarted.WhileSubscribed())

    override val currentPosition: Flow<Long> = positionTicker
        .map { controller?.currentPosition ?: 0L }
        .distinctUntilChanged()

    override val duration: Flow<Long> = positionTicker
        .map { controller?.duration?.takeIf { it > 0 } ?: 0L }
        .distinctUntilChanged()

    internal var controller: MediaController? = null

    private val listener = createPlayerListener()

    private val controllerFuture = MediaController
        .Builder(context, SessionToken(context, ComponentName(context, SynthXPlaybackService::class.java)))
        .buildAsync()

    // Completed (true or false) exactly once, when the session connection resolves. Only the
    // restore path waits on it: it runs at launch, i.e. the one moment the controller is
    // reliably not connected yet.
    private val controllerReady = CompletableDeferred<Boolean>()

    init {
        instances += this
        // Queue Songs are cached independently of Room. Observe the current song so an
        // Issues backfill becomes visible immediately, including on a later transition
        // to a previously queued song. This only changes swatches, never MediaItems.
        engineScope.launch {
            _currentSongId.collectLatest { songId ->
                if (songId == null) return@collectLatest
                SongRepositoryImpl.create(appContext).observePaletteSwatches(songId)
                    .catch { error -> Log.w(TAG, "Could not observe stored palette for $songId", error) }
                    .collect { swatches ->
                        var changed = false
                        songsBySlotId.entries.forEach { entry ->
                            if (entry.value.id == songId && entry.value.paletteSwatches != swatches) {
                                entry.setValue(entry.value.copy(paletteSwatches = swatches))
                                changed = true
                            }
                        }
                        if (changed) {
                            refreshQueue()
                            refreshUpcomingQueue()
                            publishCurrentSlot(_currentSlotId.value)
                        }
                    }
            }
        }
        // A rescan can replace one content id while several independently keyed queue slots
        // reference it. Refresh every matching value without changing any stable slot key.
        engineScope.launch {
            SongRemapEvents.events.collect { remap ->
                val affectedSlotIds = songsBySlotId
                    .filterValues { it.id == remap.oldId }
                    .keys
                    .toList()
                if (affectedSlotIds.isNotEmpty()) {
                    affectedSlotIds.forEach { slotId ->
                        songsBySlotId[slotId] = remap.newSong
                        artworkLoadedSlotIds.remove(slotId)
                    }
                    orderStore.remapSong(remap.oldId, remap.newSong.id)
                    val ctrl = controller
                    affectedSlotIds.forEach { slotId ->
                        // A null index simply means that slot is not in the loaded window —
                        // an ordinary answer now, not a drift signal. Its refreshed Song is
                        // already in songsBySlotId, so it comes back correct whenever the
                        // window next reaches it.
                        val index = ctrl?.let { indexOfMediaId(it, slotId) } ?: return@forEach
                        ctrl.replaceMediaItem(
                            index,
                            buildMediaItem(slotId, remap.newSong, artworkData = null),
                        )
                    }
                    refreshQueue()
                    refreshUpcomingQueue()
                    val currentSlotId = _currentSlotId.value
                    if (currentSlotId != null && currentSlotId in affectedSlotIds) {
                        publishCurrentSlot(currentSlotId)
                    }
                    persistQueue()
                    prefetchArtwork()
                }
            }
        }
        // Complete delete removes the song's file as well as its row, so a queue slot still
        // pointing at it is not merely stale (the remap case above) — it is unplayable. Every
        // live engine subscribes independently, which is what makes one emission reach all of
        // them; see SongDeletionEvents.
        engineScope.launch {
            SongDeletionEvents.events.collect { songId ->
                removeDeletedSongInternal(songId)
            }
        }
    }

    init {
        controllerFuture.addListener(
            {
                // A suspend hop is needed here (see adoptRunningTimeline) when reconnecting
                // to a still-running service, so this is a coroutine launch rather than the
                // plain synchronous body it used to be — engineScope's Main.immediate
                // dispatcher keeps `controller` assignment and controllerReady.complete on
                // the main thread either way.
                engineScope.launch {
                    try {
                        val ctrl = controllerFuture.get()
                        controller = ctrl
                        ctrl.addListener(listener)
                        // Adopt whatever repeat state the session already holds (a service
                        // that outlived the previous MediaController keeps its timeline).
                        // Shuffle has no media3-side state to adopt any more — restoreSavedQueue
                        // (or a fresh playQueue) is what seeds _shuffleEnabled now.
                        _repeatMode.value = ctrl.repeatMode.toRepeatMode()
                        val connectionPath = if (ctrl.mediaItemCount > 0 && orderStore.isEmpty) {
                            // Reconnecting to a still-running service: the order store and
                            // songsBySlotId are otherwise only populated by
                            // playQueueInternal/restoreSavedQueueInternal, neither of which
                            // runs here — must happen before refreshQueue() below or the queue
                            // flows end up empty against a player that is actually playing.
                            adoptRunningTimeline(ctrl)
                            "adopting existing running timeline"
                        } else if (ctrl.mediaItemCount > 0) {
                            "using existing engine queue"
                        } else {
                            "starting fresh; persisted restore pending"
                        }
                        val currentSlotId = ctrl.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }
                        refreshQueue()
                        publishCurrentSlot(currentSlotId)
                        refreshCurrentIndex()
                        refreshUpcomingQueue()
                        refreshNavigationState()
                        // The service's surviving timeline is whatever window was loaded when
                        // this process died; reconcile it against the order just adopted.
                        maintainWindow(force = true)
                        prefetchArtwork()
                        Log.d(
                            TAG,
                            "Session connected: $connectionPath; song=${_currentSong.value?.id}, " +
                                "slot=${_currentSlotId.value}, queue=${orderStore.size}",
                        )
                        controllerReady.complete(true)
                    } catch (_: Exception) {
                        // Service connection failed — playback methods will be no-ops until retry
                        Log.d(TAG, "Session connection failed")
                        controllerReady.complete(false)
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override suspend fun playQueue(songs: List<Song>, startIndex: Int) = playQueueInternal(songs, startIndex)

    override suspend fun restoreSavedQueue() = restoreSavedQueueInternal()

    override fun persistPlaybackState() {
        controller ?: return
        if (orderStore.isEmpty) return
        // persistQueue writes the order store, never the loaded window — see its own kdoc.
        persistQueue()
    }

    /**
     * Resolved against the order store rather than delegating to `MediaController.seekToNext`.
     * The loaded window ends well before the queue does, so media3's own traversal cannot
     * answer "what plays next" for anything but the next few dozen songs; [seekToLiveIndex]
     * seeks within the window when it can and rebuilds around the target when it cannot.
     */
    override fun next() {
        val target = nextLiveIndex() ?: return
        seekToLiveIndex(target, play = false)
    }

    /**
     * Hand-rolled rather than `MediaController.seekToPrevious()` for two reasons, both
     * consequences of the timeline being a window: media3 would wrap off the *window's* first
     * item under Repeat All (jumping forward ~50 songs, not back one), and "is there a previous
     * item" has to be asked of the order store to know whether the real queue start has been
     * reached. Keeps media3's own convention of restarting the current item when more than
     * [MediaController.getMaxSeekToPreviousPosition] into it.
     *
     * At the window's rear edge this restarts the current item instead of walking further back
     * — the accepted, deliberate limit of keeping only [WINDOW_BACK] songs of history loaded.
     */
    override fun previous() {
        val ctrl = controller ?: return
        if (ctrl.currentPosition > ctrl.maxSeekToPreviousPosition) {
            ctrl.seekTo(/* positionMs = */ 0)
            return
        }
        val loadedIndex = ctrl.currentMediaItemIndex
        if (loadedIndex != C.INDEX_UNSET && loadedIndex > 0) {
            ctrl.seekTo(loadedIndex - 1, C.TIME_UNSET)
            return
        }
        // Rear edge of the window. Wrapping to the end of the queue is only correct when this
        // genuinely is the queue's first slot, which for a queue small enough to fit entirely
        // in one window is exactly the behaviour it had before windowing.
        val current = orderStore.indexOfSlot(_currentSlotId.value)
        if (_repeatMode.value == RepeatMode.All && current == 0 && orderStore.size > 1) {
            seekToLiveIndex(orderStore.size - 1, play = false)
        } else {
            ctrl.seekTo(/* positionMs = */ 0)
        }
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) = setShuffleEnabledInternal(enabled)

    override fun setRepeatMode(mode: RepeatMode) {
        controller?.repeatMode = mode.toPlayerRepeatMode()
    }

    override fun playNext(song: Song): Boolean {
        playNextInternal(song)
        return true
    }

    override fun addToQueue(song: Song): Boolean {
        addToQueueInternal(song)
        return true
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) = moveQueueItemInternal(fromIndex, toIndex)

    override fun removeQueueItem(slotId: String) = removeQueueItemInternal(slotId)

    override fun seekToQueueItem(index: Int) = seekToQueueItemInternal(index)

    override fun pause() {
        controller?.pause()
        // Pause and app-backgrounded are the only position writes: saving on every tick
        // would mean a database write twice a second for a whole album.
        persistPlaybackState()
    }

    override fun resume() {
        controller?.play()
    }

    override fun stop() {
        controller?.stop()
        publishCurrentSlot(null)
        // The order store is untouched (the queue still exists, playback just left it), but
        // everything derived from "where the cursor is" has to follow the cursor to nowhere.
        refreshCurrentIndex()
        refreshUpcomingQueue()
        refreshNavigationState()
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    override fun release() {
        releaseInternal()
    }

    /** Stops playback without cancelling accepted FIFO writes; restore drains those next. */
    private fun releaseForRestore() {
        releaseInternal()
    }

    // [persistence] is an app-process singleton (see QueuePersistence.create) shared with
    // whichever other component (another engine instance recreated after this one, or the
    // playback service's onTaskRemoved) still needs to enqueue writes — it must never be
    // released here just because this one engine instance is tearing down. It is only ever
    // permanently closed via QueuePersistence.quiesceAllForRestore(), called from restore's
    // own machinery ahead of the restart-required terminal state.
    private fun releaseInternal() {
        artworkJob?.cancel()
        engineScope.cancel()
        // Unblocks a restore still waiting on a connection that will now never land.
        controllerReady.complete(false)
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        // Cleared so the `controller?.` guards in the playback methods actually
        // short-circuit after release instead of calling into a released instance.
        controller = null
        instances.remove(this)
    }

    internal suspend fun awaitController(): MediaController? {
        controllerReady.await()
        return controller
    }

    internal fun Int.toRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.One
        Player.REPEAT_MODE_ALL -> RepeatMode.All
        else -> RepeatMode.Off
    }

    private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.Off -> Player.REPEAT_MODE_OFF
        RepeatMode.All -> Player.REPEAT_MODE_ALL
        RepeatMode.One -> Player.REPEAT_MODE_ONE
    }

    /** Publishes every representation of the current slot from the engine-owned song cache. */
    internal fun publishCurrentSlot(slotId: String?) {
        val song = slotId?.let(songsBySlotId::get)
        _currentSlotId.value = slotId
        _currentSongId.value = song?.id
        _currentSong.value = song
    }

    internal companion object {
        const val TAG = "SynthXQueue"
        private val instances = ConcurrentHashMap.newKeySet<ExoPlayerEngine>()

        /** Restore permanently stops every owner that can enqueue queue persistence. */
        suspend fun releaseAllForRestore() {
            val engines = instances.toList()
            withContext(Dispatchers.Main.immediate) {
                engines.forEach {
                    it.stop()
                    it.releaseForRestore()
                }
            }
        }
    }
}
