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

    /**
     * True while ExoPlayer is rebuffering (STATE_BUFFERING) — a stalled/slow-storage track,
     * distinct from an intentional pause. UI consumers show a spinner in place of the
     * play/pause icon rather than leaving the two states visually identical.
     */
    val isBuffering: Flow<Boolean>
    val currentSongId: Flow<String?>
    val currentPosition: Flow<Long>
    val duration: Flow<Long>
    val errors: Flow<PlaybackError>

    /** The playback timeline, in queue order (unaffected by shuffle). */
    val queue: Flow<List<Song>>

    /** Index into [queue] of the item currently playing, or -1 when the queue is empty. */
    val currentIndex: Flow<Int>

    /**
     * The real forward playback order starting immediately after the current item, and
     * excluding it — i.e. exactly what [next] will walk through, one call at a time. When
     * [shuffleEnabled] is on this is shuffle order, not timeline order: it is built by
     * walking media3's own next-window traversal, so what this shows is guaranteed to match
     * what actually plays next rather than a display-only approximation of it.
     */
    val upcomingQueue: Flow<List<Song>>

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

    /**
     * Moves the item at [fromIndex] to [toIndex] within [upcomingQueue], leaving playback
     * running. A no-op while shuffle is enabled: media3's `moveMediaItem` does not reindex
     * its internal shuffle order (see [ExoPlayerEngine.moveQueueItem]'s implementation
     * comment), so honouring this call under shuffle would desync what's displayed from
     * what actually plays next. Callers are expected to also disable the affected UI, this
     * is the backstop.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int)

    /**
     * Removes the item with [songId] from [upcomingQueue]. Safe under shuffle.
     *
     * Identified by song id rather than by position — unlike [moveQueueItem] and
     * [seekToQueueItem], which still take display indices. Removal is the destructive one:
     * a position captured by the UI can be invalidated by any queue mutation that lands
     * first, and acting on a stale one removes the wrong song permanently. Implementations
     * must resolve the position fresh, at call time, from their own live queue state.
     */
    fun removeQueueItem(songId: String)

    /** Jumps playback to the item at [index] in [upcomingQueue] and plays it. */
    fun seekToQueueItem(index: Int)

    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()
}
