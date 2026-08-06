package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ExoPlayerEngine(context: Context) : PlaybackEngine {

    // Needed to resolve album-art content:// URIs through the ContentResolver
    private val appContext = context.applicationContext

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: Flow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSongId = MutableStateFlow<String?>(null)
    override val currentSongId: Flow<String?> = _currentSongId.asStateFlow()

    // replay = 0 with a 1-slot buffer: a failure that lands while nothing is collecting
    // is dropped rather than replayed later as a stale message.
    private val _errors = MutableSharedFlow<PlaybackError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val errors: Flow<PlaybackError> = _errors.asSharedFlow()

    // Last song handed to playSong. Written and read on the main thread only (the
    // setMediaItem block below and Player.Listener callbacks both run there), so it
    // needs no synchronisation. MediaItem metadata would also carry the title, but
    // reading it back after stop() depends on the controller retaining the item.
    private var currentSongTitle: String? = null

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

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentSongId.value = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                stop()
            }
        }

        // A failed prepare lands in STATE_IDLE, which onPlaybackStateChanged above
        // deliberately ignores — without this the mini player would sit on a song that
        // never plays. stop() clears currentSongId, which cascades to currentSong.
        override fun onPlayerError(error: PlaybackException) {
            val title = currentSongTitle
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
                } catch (_: Exception) {
                    // Service connection failed — playback methods will be no-ops until retry
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override suspend fun playSong(song: Song) {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)

        // Load artwork bytes in-process and embed directly — cross-process consumers
        // (notification, lock screen, Android Auto) cannot read our app-private files,
        // so an artwork URI would silently resolve to nothing on their side.
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

        val artworkData: ByteArray = withContext(Dispatchers.Default) {
            artworkBytes ?: run {
                val bitmap = GlitchBitmapGenerator.generateGlitchBitmap(song.id)
                val stream = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                stream.toByteArray()
            }
        }

        metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(song.filePath))
            .setMediaMetadata(metadataBuilder.build())
            .build()

        // MediaController is main-thread-only and the hops above may have moved us off it
        withContext(Dispatchers.Main.immediate) {
            val ctrl = controller ?: return@withContext
            currentSongTitle = song.title
            ctrl.setMediaItem(mediaItem)
            ctrl.prepare()
            ctrl.play()
        }
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
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        // Cleared so the `controller?.` guards in the playback methods actually
        // short-circuit after release instead of calling into a released instance.
        controller = null
    }
}
