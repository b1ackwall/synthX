package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.v1per.synthx.data.PlaybackCacheRevision
import com.v1per.synthx.data.SongDeletionAcknowledgment
import com.v1per.synthx.data.collectCatching
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.SongRemap
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

    private val sessionToken =
        SessionToken(context, ComponentName(context, SynthXPlaybackService::class.java))

    /**
     * Rebuilt on every (re)connection attempt — hence `var`, not `val` as before. P4-T3: the
     * original one-shot future meant a failed initial connection or a mid-session disconnection
     * was terminal for the rest of this engine's lifetime, despite comments elsewhere promising
     * retry. [connectToSession] is what actually rebuilds this now.
     */
    private var controllerFuture: ListenableFuture<MediaController> = buildControllerFuture()

    // Completed (true or false) exactly once, when the FIRST session connection attempt
    // resolves. Only the restore path waits on it: it runs at launch, i.e. the one moment the
    // controller is reliably not connected yet. Later reconnect attempts (retry after initial
    // failure, or recovery after a mid-session disconnect) do not create a second one of these —
    // restore only ever needs to know "is *a* controller usable yet", answered once.
    private val controllerReady = CompletableDeferred<Boolean>()

    // Bounded retry for BOTH the initial connection and a later mid-session disconnection —
    // see MediaController.Listener.onDisconnected below. Capped rather than infinite so a
    // permanently-gone session (service process actually killed, not just this one controller
    // dropped) doesn't retry forever; a user action that needs playback will simply find it
    // still unavailable and can retry explicitly by leaving/reopening the screen.
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    private fun buildControllerFuture(): ListenableFuture<MediaController> {
        val sessionListener = object : MediaController.Listener {
            // Session-level disconnection (the session/service itself went away or revoked
            // this controller) — distinct from a Player.Listener callback, and previously
            // nothing installed a MediaController.Listener at all (P4-T3 / R3-F09).
            override fun onDisconnected(controller: MediaController) {
                Log.w(TAG, "MediaController disconnected mid-session")
                scheduleReconnect()
            }
        }
        return controllerFutureFactory?.invoke(sessionListener)
            ?: MediaController.Builder(appContext, sessionToken)
                .setListener(sessionListener)
                .buildAsync()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val backoffMs = ReconnectPolicy.nextDelayMsOrNull(reconnectAttempts)
        if (backoffMs == null) {
            Log.w(TAG, "Giving up on reconnecting after $reconnectAttempts attempts")
            return
        }
        reconnectAttempts++
        reconnectJob = engineScope.launch {
            delay(backoffMs)
            // A fresh future every attempt — re-adding a listener to the same already-resolved
            // (failed, or now-stale) future would either fail again for the same reason or,
            // for a mid-session disconnect, do nothing at all (its listener already fired once).
            controllerFuture = buildControllerFuture()
            connectToSession()
        }
    }

    init {
        instances += this
        // Registered so SongDeletionAcknowledgment.register() knows there is at least one live
        // engine to hand a deletion off to (P4-T4) — see releaseInternal for the matching detach.
        SongDeletionAcknowledgment.engineAttached()
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

    /** The remap-collector body, split out so it can be wrapped in a try/catch above. */
    private fun handleRemap(remap: SongRemap) {
        val affectedSlotIds = songsBySlotId
            .filterValues { it.id == remap.oldId }
            .keys
            .toList()
        if (affectedSlotIds.isEmpty()) return
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

    /**
     * (Re)connects to [SynthXPlaybackService]'s session — the first connection at construction,
     * and any later reconnection [scheduleReconnect] triggers. P4-T3: previously this ran once,
     * inline in an `init` block, un-retryable by construction; splitting it out is what makes
     * retry possible at all.
     */
    private fun connectToSession() {
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
                        reconnectAttempts = 0
                        reconnectJob = null
                        // Adopt whatever repeat state the session already holds (a service
                        // that outlived the previous MediaController keeps its timeline).
                        // Shuffle has no media3-side state to adopt any more — restoreSavedQueue
                        // (or a fresh playQueue) is what seeds _shuffleEnabled now.
                        _repeatMode.value = ctrl.repeatMode.toRepeatMode()
                        // Seeded here rather than left to the next Player.Listener callback
                        // (P4-T3 / R3-F09): adding a listener does not replay the controller's
                        // current values, so without this an already-playing session (a
                        // reconnect, or adopting a still-running service) shows paused/idle
                        // until a later real transition happens to fire.
                        _isPlaying.value = ctrl.isPlaying
                        _isBuffering.value = ctrl.playbackState == Player.STATE_BUFFERING
                        // Captured before adoptRunningTimeline's suspending DB read below — see
                        // reconcileIfRevisionChanged's kdoc. This connect path is one of the
                        // three suspension points P4-T2 covers: a remap/deletion landing while
                        // this suspends is checked against a still-empty cache and discarded.
                        val revisionBeforeAdopt = PlaybackCacheRevision.current()
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
                        // The service's surviving timeline is whatever window this engine
                        // instance (or an activity-finish-while-playing predecessor, per P4-T3)
                        // last left loaded; reconcile it against the order just adopted.
                        maintainWindow(force = true)
                        prefetchArtwork()
                        Log.d(
                            TAG,
                            "Session connected: $connectionPath; song=${_currentSong.value?.id}, " +
                                "slot=${_currentSlotId.value}, queue=${orderStore.size}",
                        )
                        controllerReady.complete(true)
                        reconcileIfRevisionChanged(revisionBeforeAdopt)
                    } catch (_: Exception) {
                        Log.d(TAG, "Session connection failed")
                        // Only the FIRST attempt reports failure through controllerReady — later
                        // retries must not re-complete an already-completed CompletableDeferred
                        // (a no-op call, but reporting "false" a second time would be misleading
                        // to restoreSavedQueueInternal's one-time wait anyway).
                        controllerReady.complete(false)
                        scheduleReconnect()
                    }
                }
            },
            ContextCompat.getMainExecutor(appContext),
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

    override fun playNext(song: Song): Boolean {
        playNextInternal(song)
        return true
    }

    override fun addToQueue(song: Song): Boolean {
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
        // Unblocks a restore still waiting on a connection that will now never land.
        controllerReady.complete(false)
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        // Cleared so the `controller?.` guards in the playback methods actually
        // short-circuit after release instead of calling into a released instance.
        controller = null
        instances.remove(this)
        // Matches the attach in init — see SongDeletionAcknowledgment's kdoc (P4-T4).
        SongDeletionAcknowledgment.engineDetached()
    }

    internal suspend fun awaitController(): MediaController? {
        controllerReady.await()
        return controller
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
