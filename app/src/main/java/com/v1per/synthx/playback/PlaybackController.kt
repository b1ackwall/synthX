package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.QueueSlot
import kotlinx.coroutines.flow.Flow

class PlaybackController(private val engine: PlaybackEngine) {

    val isPlaying: Flow<Boolean> = engine.isPlaying
    val isBuffering: Flow<Boolean> = engine.isBuffering
    val currentSongId: Flow<String?> = engine.currentSongId
    val currentSong: Flow<Song?> = engine.currentSong
    val currentPosition: Flow<Long> = engine.currentPosition
    val duration: Flow<Long> = engine.duration
    val errors: Flow<PlaybackError> = engine.errors
    val queue: Flow<List<QueueSlot>> = engine.queue
    val currentIndex: Flow<Int> = engine.currentIndex
    val upcomingQueue: Flow<List<QueueSlot>> = engine.upcomingQueue
    val shuffleEnabled: Flow<Boolean> = engine.shuffleEnabled
    val repeatMode: Flow<RepeatMode> = engine.repeatMode
    val hasNext: Flow<Boolean> = engine.hasNext
    val hasPrevious: Flow<Boolean> = engine.hasPrevious
    val isQueueCustomized: Flow<Boolean> = engine.isQueueCustomized

    suspend fun playQueue(songs: List<Song>, startIndex: Int) = engine.playQueue(songs, startIndex)
    suspend fun restoreSavedQueue() = engine.restoreSavedQueue()
    fun persistPlaybackState() = engine.persistPlaybackState()
    fun next() = engine.next()
    fun previous() = engine.previous()
    suspend fun setShuffleEnabled(enabled: Boolean) = engine.setShuffleEnabled(enabled)
    fun setRepeatMode(mode: RepeatMode) = engine.setRepeatMode(mode)
    fun playNext(song: Song): Boolean = engine.playNext(song)
    fun addToQueue(song: Song): Boolean = engine.addToQueue(song)
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = engine.moveQueueItem(fromIndex, toIndex)
    /** Identity-based, not index-based — see [PlaybackEngine.removeQueueItem] for why. */
    fun removeQueueItem(slotId: String) = engine.removeQueueItem(slotId)
    fun seekToQueueItem(index: Int) = engine.seekToQueueItem(index)
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun stop() = engine.stop()
    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    fun release() = engine.release()
}
