package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.Flow

class PlaybackController(private val engine: PlaybackEngine) {

    val isPlaying: Flow<Boolean> = engine.isPlaying
    val currentSongId: Flow<String?> = engine.currentSongId

    fun playSong(song: Song) = engine.playSong(song)
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun stop() = engine.stop()
    fun release() = engine.release()
}
