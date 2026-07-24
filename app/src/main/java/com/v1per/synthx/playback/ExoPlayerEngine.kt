package com.v1per.synthx.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

class ExoPlayerEngine(context: Context) : PlaybackEngine {

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
        .build()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: Flow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSongId = MutableStateFlow<String?>(null)
    override val currentSongId: Flow<String?> = _currentSongId.asStateFlow()

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
    }

    init {
        player.addListener(listener)
    }

    override fun playSong(song: Song) {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)

        if (song.albumArtUri != null) {
            metadataBuilder.setArtworkUri(Uri.parse(song.albumArtUri))
        } else {
            val bitmap = GlitchBitmapGenerator.generateGlitchBitmap(song.id)
            val stream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            metadataBuilder.setArtworkData(stream.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(song.filePath))
            .setMediaMetadata(metadataBuilder.build())
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun resume() {
        player.play()
    }

    override fun stop() {
        player.stop()
        _currentSongId.value = null
    }

    override fun release() {
        player.removeListener(listener)
        player.release()
    }
}
