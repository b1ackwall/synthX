package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ExoPlayerEngine(context: Context) : PlaybackEngine {

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

    private val _shuffleEnabled = MutableStateFlow(false)
    override val shuffleEnabled: Flow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.Off)
    override val repeatMode: Flow<RepeatMode> = _repeatMode.asStateFlow()

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
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Queue first: PlaybackViewModel resolves the new id against the queue to
            // rebuild currentSong, so the queue must already be up to date when the id lands.
            refreshQueue()
            _currentSongId.value = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
            prefetchArtwork()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
            // Shuffle changes which item comes next, so the lookahead target moves.
            prefetchArtwork()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode.toRepeatMode()
            prefetchArtwork()
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
                } catch (_: Exception) {
                    // Service connection failed — playback methods will be no-ops until retry
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

            refreshQueue()
            prefetchArtwork()
        }
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
        refreshQueue()
        prefetchArtwork()
    }

    override fun addToQueue(song: Song) {
        val ctrl = controller ?: return
        songsById[song.id] = song
        ctrl.addMediaItem(buildMediaItem(song, artworkData = null))
        refreshQueue()
        prefetchArtwork()
    }

    override fun pause() {
        controller?.pause()
    }

    override fun resume() {
        controller?.play()
    }

    override fun stop() {
        controller?.stop()
        _currentSongId.value = null
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    override fun release() {
        artworkJob?.cancel()
        engineScope.cancel()
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        // Cleared so the `controller?.` guards in the playback methods actually
        // short-circuit after release instead of calling into a released instance.
        controller = null
    }

    // --- queue state -------------------------------------------------------------

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
}
