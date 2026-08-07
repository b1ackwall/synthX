package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.Flow

/** One-off playback failures, surfaced to the UI as transient messages. */
sealed class PlaybackError {
    data class PlaybackFailed(val songTitle: String) : PlaybackError()
}

/** Queue repeat behaviour, mapped 1:1 onto media3's Player.REPEAT_MODE_* constants. */
enum class RepeatMode {
    Off,
    All,
    One,
}

interface PlaybackEngine {
    val isPlaying: Flow<Boolean>
    val currentSongId: Flow<String?>
    val currentPosition: Flow<Long>
    val duration: Flow<Long>
    val errors: Flow<PlaybackError>

    /** The playback timeline, in queue order (unaffected by shuffle). */
    val queue: Flow<List<Song>>

    /** Index into [queue] of the item currently playing, or -1 when the queue is empty. */
    val currentIndex: Flow<Int>

    val shuffleEnabled: Flow<Boolean>
    val repeatMode: Flow<RepeatMode>

    /** Replaces the whole queue with [songs] and starts playing at [startIndex]. */
    suspend fun playQueue(songs: List<Song>, startIndex: Int)

    /**
     * Reloads the queue persisted by the previous session and prepares it *paused*, at the
     * saved item and position. A no-op when nothing was saved.
     */
    suspend fun restoreSavedQueue()

    /** Snapshots the current item index and position so a restart can resume there. */
    fun persistPlaybackState()

    fun next()
    fun previous()
    fun setShuffleEnabled(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)

    /** Inserts [song] directly after the current item. */
    fun playNext(song: Song)

    /** Appends [song] to the end of the queue. */
    fun addToQueue(song: Song)

    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()
}
