package com.v1per.synthx.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExoPlayerEngine(context: Context) : PlaybackEngine {

    private val player = ExoPlayer.Builder(context).build()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: Flow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSongId = MutableStateFlow<String?>(null)
    override val currentSongId: Flow<String?> = _currentSongId.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentSongId.value = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
            }
        })
    }

    override fun playSong(song: Song) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(song.filePath))
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
        player.release()
    }
}
