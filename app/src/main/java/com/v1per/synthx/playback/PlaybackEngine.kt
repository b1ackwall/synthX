package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.Flow

interface PlaybackEngine {
    val isPlaying: Flow<Boolean>
    val currentSongId: Flow<String?>
    val currentPosition: Flow<Long>
    val duration: Flow<Long>
    suspend fun playSong(song: Song)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()
}
