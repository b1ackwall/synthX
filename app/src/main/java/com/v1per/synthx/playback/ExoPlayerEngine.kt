package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ExoPlayerEngine(
    context: Context,
    private val persistence: QueuePersistence,
) : PlaybackEngine {

    // Needed to resolve album-art content:// URIs through the ContentResolver
    private val appContext = context.applicationContext

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: Flow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSongId = MutableStateFlow<String?>(null)
    override val currentSongId: Flow<String?> = _currentSongId.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    override val queue: Flow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: Flow<Int> = _currentIndex.asStateFlow()

    private val _upcomingQueue = MutableStateFlow<List<Song>>(emptyList())
    override val upcomingQueue: Flow<List<Song>> = _upcomingQueue.asStateFlow()

    // Parallel to _upcomingQueue.value: upcomingTimelineIndices[i] is the real timeline
    // window index of upcomingQueue[i]. Recomputed alongside it in refreshUpcomingQueue();
    // moveQueueItem/removeQueueItem/seekToQueueItem translate the display index the UI
    // hands them through this before touching the controller.
    private var upcomingTimelineIndices: List<Int> = emptyList()

    private val _shuffleEnabled = MutableStateFlow(false)
    override val shuffleEnabled: Flow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.Off)
    override val repeatMode: Flow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isQueueCustomized = MutableStateFlow(false)
    override val isQueueCustomized: Flow<Boolean> = _isQueueCustomized.asStateFlow()

    // Recomputed imperatively (see refreshNavigationState) rather than derived from other
    // flows: ctrl.hasNextMediaItem() is a snapshot query with no Flow of its own, and it
    // needs the shuffle- and repeat-aware answer only the controller can give.
    private val _hasNext = MutableStateFlow(false)
    override val hasNext: Flow<Boolean> = _hasNext.asStateFlow()

    // Pure function of currentSongId: whenever a current item exists, previous() always
    // does something (restart, or jump — see previous()'s kdoc), so this needs no
    // separate refresh hook.
    override val hasPrevious: Flow<Boolean> = currentSongId.map { it != null }

    // replay = 0 with a 1-slot buffer: a failure that lands while nothing is collecting
    // is dropped rather than replayed later as a stale message.
    private val _errors = MutableSharedFlow<PlaybackError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val errors: Flow<PlaybackError> = _errors.asSharedFlow()

    // MediaItem.mediaId holds song.id, but a MediaItem cannot carry the whole Song back,
    // so the timeline is re-hydrated into Songs through this cache. Written and read on
    // the main thread only (playback mutations and Player.Listener callbacks both run
    // there), so it needs no synchronisation.
    private val songsById = LinkedHashMap<String, Song>()

    // Song ids whose MediaItem in the current timeline already carries artwork bytes.
    private val artworkLoadedIds = mutableSetOf<String>()

    // Main.immediate to match MediaController's main-thread requirement; the artwork
    // work itself hops to IO/Default and comes back.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null

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

    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refreshQueue()
            refreshUpcomingQueue()
            refreshNavigationState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Queue first: PlaybackViewModel resolves the new id against the queue to
            // rebuild currentSong, so the queue must already be up to date when the id lands.
            refreshQueue()
            _currentSongId.value = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
            // The current item moved, so "everything after it" moves with it.
            refreshUpcomingQueue()
            refreshNavigationState()
            prefetchArtwork()
        }

        // Persisted here rather than in the setters below so a change made anywhere —
        // including the notification or another controller on the same session — is saved.
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
            persistence.shuffleEnabled = shuffleModeEnabled
            // Shuffle changes which item comes next, so the lookahead target moves, and the
            // upcoming order itself — what QueueScreen displays — changes entirely.
            refreshUpcomingQueue()
            prefetchArtwork()
            refreshNavigationState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode.toRepeatMode()
            persistence.repeatMode = repeatMode
            prefetchArtwork()
            refreshNavigationState()
        }

        /**
         * STATE_ENDED means the *whole* timeline finished, not that one item ended —
         * media3 auto-advances between queue items without leaving STATE_READY. The
         * hasNextMediaItem() guard is belt-and-braces: it accounts for repeat mode and
         * shuffle order, so anything still reachable keeps playback alive and only a
         * genuinely exhausted queue falls through to stop().
         */
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val ctrl = controller ?: return
            if (!ctrl.hasNextMediaItem()) stop()
        }

        // A failed prepare lands in STATE_IDLE, which onPlaybackStateChanged above
        // deliberately ignores — without this the mini player would sit on a song that
        // never plays. stop() clears currentSongId, which cascades to currentSong.
        override fun onPlayerError(error: PlaybackException) {
            // Read the failing item's title before stop(), while the controller is still
            // positioned on it.
            val title = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
                ?: _currentSongId.value?.let { songsById[it]?.title }
            stop()
            if (title != null) {
                _errors.tryEmit(PlaybackError.PlaybackFailed(title))
            }
        }
    }

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
                    // Adopt whatever state the session already holds (a service that
                    // outlived the previous MediaController keeps its timeline).
                    _shuffleEnabled.value = ctrl.shuffleModeEnabled
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

    override suspend fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val start = startIndex.coerceIn(0, songs.lastIndex)

        // Only the starting item gets its artwork up front — building bytes for every
        // song in the queue would mean decoding/generating hundreds of bitmaps before a
        // single note plays. The rest are filled in by prefetchArtwork() as they approach.
        val startArtwork = buildArtworkData(songs[start])

        val mediaItems = songs.mapIndexed { index, song ->
            buildMediaItem(song, if (index == start) startArtwork else null)
        }

        // MediaController is main-thread-only and the hops above may have moved us off it
        withContext(Dispatchers.Main.immediate) {
            val ctrl = controller ?: return@withContext
            songsById.clear()
            songs.forEach { songsById[it.id] = it }
            artworkLoadedIds.clear()
            artworkLoadedIds.add(songs[start].id)

            ctrl.setMediaItems(mediaItems, start, C.TIME_UNSET)
            ctrl.prepare()
            ctrl.play()

            onQueueStructureChanged(fresh = true)
        }
    }

    /**
     * Restores without playing: [MediaController.prepare] buffers to STATE_READY but
     * playWhenReady stays false, so the mini player comes up loaded and paused (Apple
     * Music's behaviour) and the user starts it.
     */
    override suspend fun restoreSavedQueue() {
        val restored = persistence.loadQueue() ?: return
        val startSong = restored.songs[restored.startIndex]
        val startArtwork = buildArtworkData(startSong)

        val mediaItems = restored.songs.map { song ->
            buildMediaItem(song, if (song.id == startSong.id) startArtwork else null)
        }

        withContext(Dispatchers.Main.immediate) {
            val ctrl = awaitController() ?: return@withContext
            // Not restored over an in-progress session: the user has already started
            // something, and replacing it with last session's queue would be a surprise.
            if (ctrl.mediaItemCount > 0) return@withContext

            songsById.clear()
            restored.songs.forEach { songsById[it.id] = it }
            artworkLoadedIds.clear()
            artworkLoadedIds.add(startSong.id)

            // setMediaItems first: shuffleModeEnabled's listener (onShuffleModeEnabledChanged)
            // fires synchronously and calls refreshUpcomingQueue(), which walks the
            // controller's timeline — that timeline must already have windows in it, or
            // Timeline.getWindow throws IndexOutOfBoundsException (see refreshUpcomingQueue's
            // doc comment for the matching defensive guard).
            ctrl.setMediaItems(mediaItems, restored.startIndex, restored.positionMs)
            ctrl.shuffleModeEnabled = persistence.shuffleEnabled
            ctrl.repeatMode = persistence.repeatMode
            ctrl.prepare()

            // Not routed through onQueueStructureChanged: the persist below is conditional,
            // and a restored queue counts as pristine — the customisation flag is session
            // state that deliberately does not survive the restart.
            refreshQueue()
            refreshUpcomingQueue()
            _isQueueCustomized.value = false
            // The stored rows disagree with what actually loaded — rewrite them so the
            // saved index and the queue cannot drift further apart on the next restore.
            if (restored.droppedCount > 0) {
                Log.i(
                    TAG,
                    "Restored ${restored.songs.size} queue items, " +
                        "${restored.droppedCount} no longer available",
                )
                persistQueue()
                persistence.savePlaybackState(restored.startIndex, restored.positionMs)
            }
            prefetchArtwork()
        }
    }

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

    override fun setShuffleEnabled(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    override fun setRepeatMode(mode: RepeatMode) {
        controller?.repeatMode = mode.toPlayerRepeatMode()
    }

    override fun playNext(song: Song) {
        val ctrl = controller ?: return
        songsById[song.id] = song
        val insertAt = (ctrl.currentMediaItemIndex + 1).coerceIn(0, ctrl.mediaItemCount)
        ctrl.addMediaItem(insertAt, buildMediaItem(song, artworkData = null))
        onQueueStructureChanged(fresh = false)
    }

    override fun addToQueue(song: Song) {
        val ctrl = controller ?: return
        songsById[song.id] = song
        ctrl.addMediaItem(buildMediaItem(song, artworkData = null))
        onQueueStructureChanged(fresh = false)
    }

    /**
     * [fromIndex]/[toIndex] are positions in [upcomingQueue], translated here to real
     * timeline indices via [upcomingTimelineIndices] before touching the controller.
     *
     * Deliberately a no-op while shuffle is enabled: media3's `moveMediaItem` does not
     * reindex its internal `ShuffleOrder` (confirmed open bug, androidx/media#1381 — still
     * unfixed as of the media3 1.4.0 this project pins; a fix landed upstream in mid-2025
     * but only as an opt-in hook that `DefaultShuffleOrder` does not implement, so it does
     * nothing for us regardless). Moving a media item while shuffled would silently desync
     * what [upcomingQueue] displays from what media3 actually plays next. The UI already
     * disables the drag handle under shuffle (QueueRow); this is the backstop in case this
     * is ever called some other way.
     */
    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val ctrl = controller ?: return
        if (ctrl.shuffleModeEnabled) return
        val realFrom = upcomingTimelineIndices.getOrNull(fromIndex) ?: return
        val realTo = upcomingTimelineIndices.getOrNull(toIndex) ?: return
        ctrl.moveMediaItem(realFrom, realTo)
        onQueueStructureChanged(fresh = false)
    }

    /**
     * [index] is a position in [upcomingQueue], translated to the real timeline index via
     * [upcomingTimelineIndices]. Unlike move, remove is safe under shuffle: media3's
     * `removeMediaItem` does correctly reindex the shuffle order (it goes through
     * `ShuffleOrder.cloneAndRemove`, the same path `addMediaItem` uses via
     * `cloneAndInsert` — neither exhibits androidx/media#1381, which is specific to move).
     * The currently-playing item can no longer reach this method at all: it isn't part of
     * [upcomingQueue] any more, so there's nothing to special-case for it here.
     */
    override fun removeQueueItem(index: Int) {
        val ctrl = controller ?: return
        val realIndex = upcomingTimelineIndices.getOrNull(index) ?: return
        ctrl.removeMediaItem(realIndex)
        onQueueStructureChanged(fresh = false)
    }

    /** [index] is a position in [upcomingQueue], translated to the real timeline index. */
    override fun seekToQueueItem(index: Int) {
        val ctrl = controller ?: return
        val realIndex = upcomingTimelineIndices.getOrNull(index) ?: return
        ctrl.seekTo(realIndex, C.TIME_UNSET)
        ctrl.play()
    }

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

    // --- queue state -------------------------------------------------------------

    private suspend fun awaitController(): MediaController? {
        controllerReady.await()
        return controller
    }

    /**
     * The single hook every structural change to the timeline runs through: re-derives the
     * queue flows, writes the new structure to storage, and records whether the queue is
     * still a pristine context list or has been hand-modified.
     *
     * A future reorder or remove only has to call this — with [fresh] false, since anything
     * that is not a wholesale [playQueue] is by definition the user customising the queue.
     */
    private fun onQueueStructureChanged(fresh: Boolean) {
        refreshQueue()
        refreshUpcomingQueue()
        persistQueue()
        _isQueueCustomized.value = !fresh
        prefetchArtwork()
    }

    private fun persistQueue() {
        persistence.saveQueue(_queue.value)
    }

    /**
     * media3's own next/previous navigation treats Repeat One as Repeat Off — it ignores
     * the single-item loop and looks for a genuinely different next window — so
     * [MediaController.hasNextMediaItem] alone would disable the next button at the last
     * item even while repeating it. The `|| repeatMode == One` override matches the
     * product requirement instead: a track the user is repeating is still one they expect
     * to be able to skip off of.
     */
    private fun refreshNavigationState() {
        val ctrl = controller
        _hasNext.value = ctrl != null && (ctrl.hasNextMediaItem() || _repeatMode.value == RepeatMode.One)
    }

    /**
     * Rebuilds [queue]/[currentIndex] from the controller's timeline. The timeline is the
     * single source of truth — every mutation (setMediaItems, addMediaItem, and later
     * move/remove) lands there, so reading it back avoids keeping a parallel list in sync.
     */
    private fun refreshQueue() {
        val ctrl = controller
        if (ctrl == null) {
            _queue.value = emptyList()
            _currentIndex.value = -1
            return
        }

        val timeline = ctrl.currentTimeline
        val window = Timeline.Window()
        val songs = ArrayList<Song>(timeline.windowCount)
        for (i in 0 until timeline.windowCount) {
            val mediaId = timeline.getWindow(i, window).mediaItem.mediaId
            // Only this engine ever populates the timeline, so the cache always has the
            // Song; an unknown id (e.g. an item restored by the service from a previous
            // process) is skipped rather than faked.
            songsById[mediaId]?.let { songs.add(it) }
        }

        _queue.value = songs
        _currentIndex.value = ctrl.currentMediaItemIndex.takeIf {
            it != C.INDEX_UNSET && it < songs.size
        } ?: -1
    }

    /**
     * Rebuilds [upcomingQueue] (and the parallel [upcomingTimelineIndices] display-index ->
     * real-timeline-index map) by walking forward from the current item one window at a
     * time via [Timeline.getNextWindowIndex] with [Player.REPEAT_MODE_OFF] forced — this is
     * a single forward pass for display purposes, not the player's actual repeat setting,
     * so it terminates at the real end of the queue instead of looping. `shuffleModeEnabled`
     * is passed through for real, though: this is the exact traversal media3's own
     * seekToNext/seekToPrevious use internally (confirmed against the session library's
     * RemotableTimeline, which is what a MediaController's currentTimeline actually is),
     * so what this produces is guaranteed to match what [next] actually does next rather
     * than a display-only guess at it.
     *
     * Guarded against an empty/unready timeline: [MediaController.getCurrentMediaItemIndex]
     * returns `0`, not [C.INDEX_UNSET], when the timeline has no windows yet, so that check
     * alone doesn't catch a listener callback firing before `setMediaItems` has landed —
     * this bit us via `onShuffleModeEnabledChanged` firing mid-`restoreSavedQueue` (see that
     * method's comment). Bounds-checking against the timeline's actual window count closes
     * that off regardless of which caller manages to invoke this too early next.
     */
    private fun refreshUpcomingQueue() {
        val ctrl = controller
        val startIndex = ctrl?.currentMediaItemIndex
        val timeline = ctrl?.currentTimeline
        if (ctrl == null || timeline == null || startIndex == null ||
            startIndex == C.INDEX_UNSET || startIndex !in 0 until timeline.windowCount
        ) {
            _upcomingQueue.value = emptyList()
            upcomingTimelineIndices = emptyList()
            return
        }

        val window = Timeline.Window()
        val songs = ArrayList<Song>()
        val indices = ArrayList<Int>()

        // visited guards against an unforeseen cycle; REPEAT_MODE_OFF should already
        // terminate at C.INDEX_UNSET on its own, this is defensive insurance only.
        val visited = HashSet<Int>()
        var index = timeline.getNextWindowIndex(startIndex, Player.REPEAT_MODE_OFF, ctrl.shuffleModeEnabled)
        while (index != C.INDEX_UNSET && visited.add(index)) {
            val mediaId = timeline.getWindow(index, window).mediaItem.mediaId
            songsById[mediaId]?.let {
                songs.add(it)
                indices.add(index)
            }
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, ctrl.shuffleModeEnabled)
        }

        _upcomingQueue.value = songs
        upcomingTimelineIndices = indices
    }

    // --- artwork -----------------------------------------------------------------

    /**
     * Fills in artwork for the current item and the one after it, replacing their
     * MediaItems in place. Only the metadata differs (same mediaId, same URI), so media3
     * updates the item without interrupting playback.
     */
    private fun prefetchArtwork() {
        val ctrl = controller ?: return
        val targets = buildList {
            ctrl.currentMediaItemIndex.let { if (it != C.INDEX_UNSET) add(it) }
            ctrl.nextMediaItemIndex.let { if (it != C.INDEX_UNSET) add(it) }
        }.filter { it < ctrl.mediaItemCount }

        val pending = targets
            .map { ctrl.getMediaItemAt(it).mediaId }
            .filter { it !in artworkLoadedIds }
            .mapNotNull { songsById[it] }
        if (pending.isEmpty()) return

        // A transition invalidates the previous window, so the in-flight job is dropped
        // rather than left to write artwork for songs that have since moved out of range.
        artworkJob?.cancel()
        artworkJob = engineScope.launch {
            for (song in pending) {
                val artwork = buildArtworkData(song)
                val live = controller ?: return@launch
                val index = indexOfMediaId(live, song.id) ?: continue
                live.replaceMediaItem(index, buildMediaItem(song, artwork))
                artworkLoadedIds.add(song.id)
            }
        }
    }

    private fun indexOfMediaId(ctrl: MediaController, mediaId: String): Int? {
        for (i in 0 until ctrl.mediaItemCount) {
            if (ctrl.getMediaItemAt(i).mediaId == mediaId) return i
        }
        return null
    }

    /**
     * Load artwork bytes in-process and embed directly — cross-process consumers
     * (notification, lock screen, Android Auto) cannot read our app-private files, so an
     * artwork URI would silently resolve to nothing on their side.
     */
    private suspend fun buildArtworkData(song: Song): ByteArray {
        val artworkBytes: ByteArray? = withContext(Dispatchers.IO) {
            song.albumArtUri?.let { uriString ->
                try {
                    appContext.contentResolver
                        .openInputStream(Uri.parse(uriString))
                        ?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
            }
        }

        return withContext(Dispatchers.Default) {
            artworkBytes ?: run {
                val bitmap = GlitchBitmapGenerator.generateGlitchBitmap(song.id)
                val stream = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                stream.toByteArray()
            }
        }
    }

    private fun buildMediaItem(song: Song, artworkData: ByteArray?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .apply {
                artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(song.filePath))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun Int.toRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.One
        Player.REPEAT_MODE_ALL -> RepeatMode.All
        else -> RepeatMode.Off
    }

    private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.Off -> Player.REPEAT_MODE_OFF
        RepeatMode.All -> Player.REPEAT_MODE_ALL
        RepeatMode.One -> Player.REPEAT_MODE_ONE
    }

    private companion object {
        const val TAG = "SynthXQueue"
    }
}
