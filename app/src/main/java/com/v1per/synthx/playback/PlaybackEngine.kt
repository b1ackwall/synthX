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

    /**
     * Whether [next] would move to a genuinely different item. False only at the end of
     * the queue with repeat off; Repeat All wraps and Repeat One is treated as always
     * having a next (media3's own navigation ignores repeat-one's looping, but a track a
     * user is repeating is one they'd still expect to be able to skip off of).
     */
    val hasNext: Flow<Boolean>

    /**
     * Whether [previous] would do anything — true whenever a current item exists. Always
     * true during active playback: with nothing before it to jump to, [previous] restarts
     * the current item instead of doing nothing (see [previous]'s kdoc).
     */
    val hasPrevious: Flow<Boolean>

    /**
     * True once the queue has been hand-modified since it was last set wholesale — today
     * by playNext/addToQueue, tomorrow by reorder/remove. In-memory only: a restart or the
     * next [playQueue] puts it back to false.
     */
    val isQueueCustomized: Flow<Boolean>

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

    /**
     * Restarts the current item if more than [media3's default 3s][androidx.media3.common.C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS]
     * into it, otherwise jumps to the actual previous queue item — standard player
     * convention (Apple Music, Spotify). This is media3's own built-in `Player.seekToPrevious()`
     * behavior, not hand-rolled: it already applies this exact threshold, and already
     * falls back to restarting the current item when there is no previous one.
     */
    fun previous()
    fun setShuffleEnabled(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)

    /** Inserts [song] directly after the current item. */
    fun playNext(song: Song)

    /** Appends [song] to the end of the queue. */
    fun addToQueue(song: Song)

    /** Moves the item at [fromIndex] to [toIndex] within [queue], leaving playback running. */
    fun moveQueueItem(fromIndex: Int, toIndex: Int)

    /** Removes the item at [index] from [queue]. */
    fun removeQueueItem(index: Int)

    /** Jumps playback to the item at [index] in [queue] and plays it. */
    fun seekToQueueItem(index: Int)

    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()
}
