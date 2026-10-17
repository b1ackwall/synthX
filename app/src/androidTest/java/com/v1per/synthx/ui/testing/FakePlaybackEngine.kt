package com.v1per.synthx.ui.testing

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackEngine
import com.v1per.synthx.playback.PlaybackError
import com.v1per.synthx.playback.RepeatMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Device-side fake [PlaybackEngine] for shell and MainActivity tests: no service, no ExoPlayer.
 * Records the calls that reach it; the test drives state through the public flows. Thread-safe
 * records, because the shell calls in on the main thread and the test reads on its own thread.
 */
internal class FakePlaybackEngine(
    current: Song? = null,
    customized: Boolean = false,
    upcoming: List<Song> = emptyList(),
    playing: Boolean = false,
) : PlaybackEngine {
    data class PlayCall(val songs: List<Song>, val startIndex: Int, val shuffle: Boolean?)

    val playQueueCalls = CopyOnWriteArrayList<PlayCall>()
    val playNextCalls = CopyOnWriteArrayList<Song>()
    val nextCalls = AtomicInteger()
    val persistCalls = AtomicInteger()
    val reconcileCalls = AtomicInteger()
    val pauseCalls = AtomicInteger()
    val resumeCalls = AtomicInteger()

    val playingState = MutableStateFlow(playing)
    val errorEvents = MutableSharedFlow<PlaybackError>(extraBufferCapacity = 4)

    override val isQueueEnded: Flow<Boolean> = MutableStateFlow(false)
    override val isPlaying: Flow<Boolean> = playingState
    override val isBuffering: Flow<Boolean> = MutableStateFlow(false)
    override val currentSongId: Flow<String?> = MutableStateFlow(current?.id)
    override val currentSong: Flow<Song?> = MutableStateFlow(current)
    override val currentPosition: Flow<Long> = MutableStateFlow(0L)
    override val duration: Flow<Long> = MutableStateFlow(60_000L)
    override val errors: Flow<PlaybackError> = errorEvents
    override val queue: Flow<List<QueueSlot>> =
        MutableStateFlow(listOfNotNull(current?.let { QueueSlot("slot-0", it) }))
    override val currentIndex: Flow<Int> = MutableStateFlow(if (current == null) -1 else 0)
    override val upcomingQueue: Flow<List<QueueSlot>> =
        MutableStateFlow(upcoming.mapIndexed { i, song -> QueueSlot("slot-${i + 1}", song) })
    override val shuffleEnabled: Flow<Boolean> = MutableStateFlow(false)
    override val repeatMode: Flow<RepeatMode> = MutableStateFlow(RepeatMode.Off)
    override val hasNext: Flow<Boolean> = MutableStateFlow(upcoming.isNotEmpty())
    override val hasPrevious: Flow<Boolean> = MutableStateFlow(false)
    override val isQueueCustomized: Flow<Boolean> = MutableStateFlow(customized)

    override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) {
        playQueueCalls += PlayCall(songs, startIndex, shuffle)
    }
    override suspend fun restoreSavedQueue() {}
    override fun persistPlaybackState() { persistCalls.incrementAndGet() }
    override fun next() { nextCalls.incrementAndGet() }
    override fun previous() {}
    override suspend fun setShuffleEnabled(enabled: Boolean) {}
    override fun setRepeatMode(mode: RepeatMode) {}
    override fun playNext(song: Song): Boolean {
        playNextCalls += song
        return true
    }
    override fun addToQueue(song: Song): Boolean = true
    override fun moveQueueItem(slotId: String, toIndex: Int) {}
    override fun removeQueueItem(slotId: String) {}
    override fun seekToQueueItem(slotId: String) {}
    override fun pause() { pauseCalls.incrementAndGet() }
    override fun resume() { resumeCalls.incrementAndGet() }
    override fun stop() {}
    override fun seekTo(positionMs: Long) {}
    override fun release() {}
    override suspend fun reconcileWithDatabase() { reconcileCalls.incrementAndGet() }

    companion object {
        fun song(id: String, albumId: String? = null) = Song(
            id = id,
            title = id,
            artist = "Artist",
            album = "Album",
            duration = 60_000L,
            filePath = "file:///$id.mp3",
            albumId = albumId,
        )
    }
}
