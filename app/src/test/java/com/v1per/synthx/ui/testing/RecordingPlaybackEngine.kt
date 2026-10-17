package com.v1per.synthx.ui.testing

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackEngine
import com.v1per.synthx.playback.PlaybackError
import com.v1per.synthx.playback.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Idle engine for list-screen ViewModel tests: nothing playing, queue not customised, so a song
 * tap goes straight to [playQueue], which only records the call.
 */
class RecordingPlaybackEngine : PlaybackEngine {
    data class PlayCall(val songIds: List<String>, val startIndex: Int, val shuffle: Boolean?)

    val playQueueCalls = mutableListOf<PlayCall>()

    override val isPlaying: Flow<Boolean> = MutableStateFlow(false)
    override val isBuffering: Flow<Boolean> = emptyFlow()
    override val currentSongId: Flow<String?> = MutableStateFlow(null)
    override val currentSong: Flow<Song?> = MutableStateFlow(null)
    override val currentPosition: Flow<Long> = emptyFlow()
    override val duration: Flow<Long> = emptyFlow()
    override val errors: Flow<PlaybackError> = emptyFlow()
    override val queue: Flow<List<QueueSlot>> = MutableStateFlow(emptyList())
    override val currentIndex: Flow<Int> = MutableStateFlow(0)
    override val upcomingQueue: Flow<List<QueueSlot>> = MutableStateFlow(emptyList())
    override val shuffleEnabled: Flow<Boolean> = emptyFlow()
    override val repeatMode: Flow<RepeatMode> = emptyFlow()
    override val hasNext: Flow<Boolean> = emptyFlow()
    override val hasPrevious: Flow<Boolean> = emptyFlow()
    override val isQueueCustomized: Flow<Boolean> = MutableStateFlow(false)

    override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) {
        playQueueCalls += PlayCall(songs.map { it.id }, startIndex, shuffle)
    }
    override suspend fun restoreSavedQueue() {}
    override fun persistPlaybackState() {}
    override fun next() {}
    override fun previous() {}
    override suspend fun setShuffleEnabled(enabled: Boolean) {}
    override fun setRepeatMode(mode: RepeatMode) {}
    override fun playNext(song: Song): Boolean = true
    override fun addToQueue(song: Song): Boolean = true
    override fun moveQueueItem(slotId: String, toIndex: Int) {}
    override fun removeQueueItem(slotId: String) {}
    override fun seekToQueueItem(slotId: String) {}
    override fun pause() {}
    override fun resume() {}
    override fun stop() {}
    override fun seekTo(positionMs: Long) {}
    override fun release() {}
    override suspend fun reconcileWithDatabase() {}
}
