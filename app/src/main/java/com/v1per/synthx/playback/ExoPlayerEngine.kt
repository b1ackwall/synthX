package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.v1per.synthx.data.repository.Song
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

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

    internal val _currentSongId = MutableStateFlow<String?>(null)
    override val currentSongId: Flow<String?> = _currentSongId.asStateFlow()

    internal val _queue = MutableStateFlow<List<Song>>(emptyList())
    override val queue: Flow<List<Song>> = _queue.asStateFlow()

    internal val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: Flow<Int> = _currentIndex.asStateFlow()

    internal val _upcomingQueue = MutableStateFlow<List<Song>>(emptyList())
    override val upcomingQueue: Flow<List<Song>> = _upcomingQueue.asStateFlow()

    // Parallel to _upcomingQueue.value: upcomingTimelineIndices[i] is the real timeline
    // window index of upcomingQueue[i]. Recomputed alongside it in refreshUpcomingQueue();
    // moveQueueItem/removeQueueItem/seekToQueueItem translate the display index the UI
    // hands them through this before touching the controller.
    internal var upcomingTimelineIndices: List<Int> = emptyList()

    internal val _shuffleEnabled = MutableStateFlow(false)
    override val shuffleEnabled: Flow<Boolean> = _shuffleEnabled.asStateFlow()

    /**
     * Owns the app-managed queue-ordering "baseline" (see [QueueBaselineManager]'s own
     * kdoc for what that means and why it exists) — distinct from [_queue], which is
     * whatever order is actually live in the timeline right now (shuffled or not).
     */
    internal val baselineManager = QueueBaselineManager(persistence)

    internal val _repeatMode = MutableStateFlow(RepeatMode.Off)
    override val repeatMode: Flow<RepeatMode> = _repeatMode.asStateFlow()

    internal val _isQueueCustomized = MutableStateFlow(false)
    override val isQueueCustomized: Flow<Boolean> = _isQueueCustomized.asStateFlow()

    // Recomputed imperatively (see refreshNavigationState) rather than derived from other
    // flows: ctrl.hasNextMediaItem() is a snapshot query with no Flow of its own, and it
    // needs the shuffle- and repeat-aware answer only the controller can give.
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

    // MediaItem.mediaId holds song.id, but a MediaItem cannot carry the whole Song back,
    // so the timeline is re-hydrated into Songs through this cache. Written and read on
    // the main thread only (playback mutations and Player.Listener callbacks both run
    // there), so it needs no synchronisation.
    internal val songsById = LinkedHashMap<String, Song>()

    // Song ids whose MediaItem in the current timeline already carries artwork bytes.
    internal val artworkLoadedIds = mutableSetOf<String>()

    // Main.immediate to match MediaController's main-thread requirement; the artwork
    // work itself hops to IO/Default and comes back.
    internal val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var artworkJob: Job? = null

    // Cold polling flows: collected on the caller's dispatcher (the ViewModel collects on
    // Main.immediate via stateIn), which satisfies MediaController's main-thread requirement.
    override val currentPosition: Flow<Long> = flow {
        while (true) {
            emit(controller?.currentPosition ?: 0L)
            delay(500)
        }
    }.distinctUntilChanged()

    override val duration: Flow<Long> = flow {
        while (true) {
            emit(controller?.duration?.takeIf { it > 0 } ?: 0L)
            delay(500)
        }
    }.distinctUntilChanged()

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
        controllerFuture.addListener(
            {
                try {
                    val ctrl = controllerFuture.get()
                    controller = ctrl
                    ctrl.addListener(listener)
                    // Adopt whatever repeat state the session already holds (a service
                    // that outlived the previous MediaController keeps its timeline).
                    // Shuffle has no media3-side state to adopt any more — restoreSavedQueue
                    // (or a fresh playQueue) is what seeds _shuffleEnabled now.
                    _repeatMode.value = ctrl.repeatMode.toRepeatMode()
                    refreshQueue()
                    refreshUpcomingQueue()
                    refreshNavigationState()
                    controllerReady.complete(true)
                } catch (_: Exception) {
                    // Service connection failed — playback methods will be no-ops until retry
                    controllerReady.complete(false)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override suspend fun playQueue(songs: List<Song>, startIndex: Int) = playQueueInternal(songs, startIndex)

    override suspend fun restoreSavedQueue() = restoreSavedQueueInternal()

    override fun persistPlaybackState() {
        val ctrl = controller ?: return
        val index = ctrl.currentMediaItemIndex
        if (index == C.INDEX_UNSET || ctrl.mediaItemCount == 0) return
        persistence.savePlaybackState(index, ctrl.currentPosition)
    }

    override fun next() {
        controller?.seekToNext()
    }

    override fun previous() {
        controller?.seekToPrevious()
    }

    override fun setShuffleEnabled(enabled: Boolean) = setShuffleEnabledInternal(enabled)

    override fun setRepeatMode(mode: RepeatMode) {
        controller?.repeatMode = mode.toPlayerRepeatMode()
    }

    override fun playNext(song: Song) = playNextInternal(song)

    override fun addToQueue(song: Song) = addToQueueInternal(song)

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) = moveQueueItemInternal(fromIndex, toIndex)

    override fun removeQueueItem(index: Int) = removeQueueItemInternal(index)

    override fun seekToQueueItem(index: Int) = seekToQueueItemInternal(index)

    override fun pause() {
        controller?.pause()
        // Pause and app-backgrounded are the only position writes: saving on every tick
        // would mean a SharedPreferences commit twice a second for a whole album.
        persistPlaybackState()
    }

    override fun resume() {
        controller?.play()
    }

    override fun stop() {
        controller?.stop()
        _currentSongId.value = null
        refreshNavigationState()
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    override fun release() {
        artworkJob?.cancel()
        engineScope.cancel()
        // Unblocks a restore still waiting on a connection that will now never land.
        controllerReady.complete(false)
        persistence.release()
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        // Cleared so the `controller?.` guards in the playback methods actually
        // short-circuit after release instead of calling into a released instance.
        controller = null
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

    internal companion object {
        const val TAG = "SynthXQueue"
    }
}
