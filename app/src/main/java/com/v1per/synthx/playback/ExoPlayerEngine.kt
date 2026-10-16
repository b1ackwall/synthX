package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.v1per.synthx.data.PlaybackCacheRevision
import com.v1per.synthx.data.SongDeletionAcknowledgment
import com.v1per.synthx.data.collectCatching
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
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
    /**
     * Test seam only — production never passes it, and `null` keeps the real
     * `MediaController.Builder` path byte-for-byte. Called once per (re)connection attempt in place
     * of building the controller future, and handed the [MediaController.Listener] the engine
     * wants installed so a fake can still delegate to a real connection. It lets a test fail the
     * first connection attempt (retry), or hold the connection open (deterministically suspending
     * a queue load at `awaitController`).
     */
    internal val controllerFutureFactory: ((MediaController.Listener) -> ListenableFuture<MediaController>)? = null,
) : PlaybackEngine {

    // Needed to resolve album-art content:// URIs through the ContentResolver
    internal val appContext = context.applicationContext

    internal val _isPlaying = MutableStateFlow(false)
    override val isPlaying: Flow<Boolean> = _isPlaying.asStateFlow()

    internal val _isBuffering = MutableStateFlow(false)
    override val isBuffering: Flow<Boolean> = _isBuffering.asStateFlow()

    internal val _isQueueEnded = MutableStateFlow(false)
    override val isQueueEnded: Flow<Boolean> = _isQueueEnded.asStateFlow()

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

    internal val listener = createPlayerListener()

    /**
     * How system transport (notification, headset, lock screen) reaches the same navigation rules
     * as the in-app buttons — see [SystemNavigationPlayer]. The service's session wraps its raw
     * player, which only knows the loaded window; this is what answers for the full order.
     */
    private val systemNavigation = object : SystemNavigationHandler {
        override val isReady: Boolean
            get() = controller != null && !orderStore.isEmpty &&
                orderStore.indexOfSlot(_currentSlotId.value) >= 0

        override fun hasNext(): Boolean = nextLiveIndex() != null

        // Whenever a current item exists, previous() always does something (restart or jump).
        override fun hasPrevious(): Boolean = true

        override fun seekToNext() = next()

        override fun seekToPrevious() = previous()
    }

    internal val sessionToken =
        SessionToken(context, ComponentName(context, SynthXPlaybackService::class.java))

    /**
     * Rebuilt on every (re)connection attempt — hence `var`, not `val` as before. P4-T3: the
     * original one-shot future meant a failed initial connection or a mid-session disconnection
     * was terminal for the rest of this engine's lifetime, despite comments elsewhere promising
     * retry. [connectToSession] is what actually rebuilds this now.
     */
    internal var controllerFuture: ListenableFuture<MediaController> = buildControllerFuture()

    // Where the session connection stands, across the initial connection, its retries and any
    // later mid-session reconnect — what awaitController waits on (issue #175; see
    // ControllerConnection's kdoc for the one-shot deferred this replaced). Bounded retry for
    // BOTH the initial connection and a mid-session disconnection, capped rather than infinite
    // so a permanently-gone session doesn't retry forever; a later playback request re-arms one
    // fresh round (see awaitController) instead of the engine staying dead until the Activity is
    // relaunched.
    internal val connection = ControllerConnection()
    internal var reconnectJob: Job? = null

    init {
        instances += this
        // Registered so SongDeletionAcknowledgment.register() knows there is at least one live
        // engine to hand a deletion off to (P4-T4) — see releaseInternal for the matching detach.
        SongDeletionAcknowledgment.engineAttached()
        // A rescan can replace one content id while several independently keyed queue slots
        // reference it. Refresh every matching value without changing any stable slot key.
        //
        // collectCatching gives each event its own exception boundary (P4-T2 / R3-F07):
        // SupervisorJob only isolates THIS launch from its siblings, it does not protect one
        // collect{} invocation from a later one — an uncaught exception here would otherwise
        // permanently kill this collector coroutine, silently dropping every remap for the rest
        // of the process.
        engineScope.launch {
            SongRemapEvents.events.collectCatching(
                onError = { remap, e ->
                    Log.e(TAG, "Remap handling failed for oldId=${remap.oldId}; continuing", e)
                },
            ) { remap -> handleRemap(remap) }
        }
        // Complete delete removes the song's file as well as its row, so a queue slot still
        // pointing at it is not merely stale (the remap case above) — it is unplayable. Every
        // live engine subscribes independently, which is what makes one emission reach all of
        // them; see SongDeletionEvents. Same per-event exception boundary as the remap collector
        // above, for the same reason.
        engineScope.launch {
            SongDeletionEvents.events.collectCatching(
                onError = { deletion, e ->
                    Log.e(TAG, "Deletion handling failed for songId=${deletion.songId}; continuing", e)
                },
            ) { deletion ->
                try {
                    removeDeletedSongInternal(deletion.songId)
                } finally {
                    // Signalled regardless of songId even being in THIS engine's queue, and
                    // regardless of whether handling above succeeded — the repository's handoff
                    // wait (P4-T4) only needs "every live engine has reacted to this event",
                    // not "every live engine actually held this song".
                    SongDeletionAcknowledgment.signalCleared(deletion.songId)
                }
            }
        }
    }

    init {
        SystemNavigationBridge.attach(systemNavigation)
        connectToSession()
    }

    // P4-T7 / R3-F13: bounded periodic checkpoint during long, uninterrupted single-track
    // playback — see periodicCheckpointDriver's kdoc. A plain collect rather than collectLatest
    // here: the driver itself already restarts its internal delay loop on every isPlaying
    // transition, so there is nothing further for the collector to cancel/restart.
    init {
        engineScope.launch {
            periodicCheckpointDriver(_isPlaying).collect { checkpointCursor() }
        }
    }

    override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) = playQueueInternal(songs, startIndex, shuffle)

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
     * The decision itself is [previousAction], shared with system transport via
     * [systemNavigation].
     */
    override fun previous() {
        val ctrl = controller ?: return
        val action = previousAction(
            positionMs = ctrl.currentPosition,
            restartThresholdMs = ctrl.maxSeekToPreviousPosition,
            loadedIndex = ctrl.currentMediaItemIndex,
            repeatMode = _repeatMode.value,
            currentOrderIndex = orderStore.indexOfSlot(_currentSlotId.value),
            orderSize = orderStore.size,
        )
        when (action) {
            // Same idle/ended recovery seekToLiveIndex applies — the two direct-seek branches
            // seek the controller without going through it, so they need their own call.
            PreviousAction.RestartCurrent -> {
                applyIdleRecovery(ctrl)
                ctrl.seekTo(/* positionMs = */ 0)
            }
            is PreviousAction.SeekLoaded -> {
                applyIdleRecovery(ctrl)
                ctrl.seekTo(action.loadedIndex, C.TIME_UNSET)
            }
            is PreviousAction.SeekOrder -> seekToLiveIndex(action.orderIndex, play = false)
        }
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) = setShuffleEnabledInternal(enabled)

    override fun setRepeatMode(mode: RepeatMode) {
        controller?.repeatMode = mode.toPlayerRepeatMode()
    }

    // Checked against the connection phase, not `controller != null`: during a mid-session
    // reconnect `controller` still holds the dropped instance, and an insert into it would
    // update the order store while media3 ignores the timeline edit (issue #175).
    override fun playNext(song: Song): Boolean {
        if (!connection.isConnected) return reportUnavailable("playNext")
        playNextInternal(song)
        return true
    }

    override fun addToQueue(song: Song): Boolean {
        if (!connection.isConnected) return reportUnavailable("addToQueue")
        addToQueueInternal(song)
        return true
    }

    override fun moveQueueItem(slotId: String, toIndex: Int) = moveQueueItemInternal(slotId, toIndex)

    override fun removeQueueItem(slotId: String) = removeQueueItemInternal(slotId)

    override fun seekToQueueItem(slotId: String) = seekToQueueItemInternal(slotId)

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
        SystemNavigationBridge.detach(systemNavigation)
        artworkJob?.cancel()
        reconnectJob?.cancel()
        engineScope.cancel()
        // Unblocks any request still waiting on a connection that will now never land. Before
        // releaseFuture below, whose onDisconnected callback must find the engine released.
        connection.release()
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        // Cleared so the `controller?.` guards in the playback methods actually
        // short-circuit after release instead of calling into a released instance.
        controller = null
        instances.remove(this)
        // Matches the attach in init — see SongDeletionAcknowledgment's kdoc (P4-T4).
        SongDeletionAcknowledgment.engineDetached()
    }

    /**
     * The `onStart`/`onResume` recovery pass (P4-T2): re-verifies this engine's whole cache
     * against the database, independent of whether [PlaybackCacheRevision] moved at all — this is
     * the general-purpose backstop for a missed emission (a worker interrupted after its DB
     * commit but before it finished emitting; a collector that was down when an event fired),
     * which a revision comparison alone cannot catch since it only proves "an event was
     * published", not "every live engine received it". [MainActivity] calls this from `onStart`,
     * the one foregrounding signal this single-Activity app has (see its own kdoc for why it does
     * not use `ProcessLifecycleOwner`).
     */
    override suspend fun reconcileWithDatabase() {
        reconcileCacheWithDatabase()
    }

    internal fun Int.toRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.One
        Player.REPEAT_MODE_ALL -> RepeatMode.All
        else -> RepeatMode.Off
    }

    internal fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
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
