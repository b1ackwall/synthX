package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.Flow

class PlaybackController(private val engine: PlaybackEngine) {

    val isPlaying: Flow<Boolean> = engine.isPlaying
    val currentSongId: Flow<String?> = engine.currentSongId
    val currentPosition: Flow<Long> = engine.currentPosition
    val duration: Flow<Long> = engine.duration
    val errors: Flow<PlaybackError> = engine.errors
    val queue: Flow<List<Song>> = engine.queue
    val currentIndex: Flow<Int> = engine.currentIndex
    val shuffleEnabled: Flow<Boolean> = engine.shuffleEnabled
    val repeatMode: Flow<RepeatMode> = engine.repeatMode
    val isQueueCustomized: Flow<Boolean> = engine.isQueueCustomized

    suspend fun playQueue(songs: List<Song>, startIndex: Int) = engine.playQueue(songs, startIndex)
    suspend fun restoreSavedQueue() = engine.restoreSavedQueue()
    fun persistPlaybackState() = engine.persistPlaybackState()
    fun next() = engine.next()
    fun previous() = engine.previous()
    fun setShuffleEnabled(enabled: Boolean) = engine.setShuffleEnabled(enabled)
    fun setRepeatMode(mode: RepeatMode) = engine.setRepeatMode(mode)
    fun playNext(song: Song) = engine.playNext(song)
    fun addToQueue(song: Song) = engine.addToQueue(song)
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun stop() = engine.stop()
    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    fun release() = engine.release()
}
