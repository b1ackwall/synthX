package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.QueueSlot
import kotlinx.coroutines.flow.Flow

/** One-off playback failures, surfaced to the UI as transient messages. */
sealed class PlaybackError {
    data class PlaybackFailed(val songTitle: String) : PlaybackError()

    /**
     * A request that needs the playback session could not reach it: the engine is between
     * connection attempts to [SynthXPlaybackService] and a queue insert can't wait, or a queue
     * load waited out a whole round of attempts without one succeeding (issue #175).
     */
    data object ServiceUnavailable : PlaybackError()
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
    /** Content identity ([Song.id]) of the current slot, used for library-row highlighting. */
    val currentSongId: Flow<String?>
    /** Fully resolved current song, published by the engine from its slot-to-song lookup. */
    val currentSong: Flow<Song?>
    val currentPosition: Flow<Long>
    val duration: Flow<Long>
    val errors: Flow<PlaybackError>

    /**
     * The full intended play order, in its entirety; app-managed shuffle physically reorders
     * these slots. Note this is *not* what the player has loaded — implementations are free to
     * keep only a bounded window of it live (see [ExoPlayerEngine]'s
     * [QueueOrderStore]/`maintainWindow`) — but it is the order playback will actually follow.
     */
    val queue: Flow<List<QueueSlot>>

    /** Index into [queue] of the item currently playing, or -1 when the queue is empty. */
    val currentIndex: Flow<Int>

    /**
     * The real forward playback order starting immediately after the current item, and
     * excluding it — i.e. exactly what [next] will walk through, one call at a time — for the
     * whole rest of the queue, not just the part the player currently holds. When
     * [shuffleEnabled] is on this is shuffle order: the order is app-managed end to end, so
     * what this shows is what actually plays next rather than a display-only approximation.
     */
    val upcomingQueue: Flow<List<QueueSlot>>

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
     * convention (Apple Music, Spotify), and it likewise falls back to restarting the current
     * item when there is no previous one.
     *
     * How far back "previous" can walk is bounded by whatever history the implementation keeps
     * loaded — for [ExoPlayerEngine] that is [WINDOW_BACK] items, after which this restarts the
     * current item rather than extending the loaded window backwards. An accepted, deliberate
     * limit: extending it would mean a timeline mutation in the middle of exactly the kind of
     * rapid interaction windowing exists to keep cheap.
     */
    fun previous()

    /**
     * Suspend so implementations can order the reorder against the main thread rather than
     * assuming a caller's dispatcher — [ExoPlayerEngine] confines the whole operation to
     * `Main.immediate`, since it is now a records-only list permutation plus a bounded window
     * reconciliation. (It no longer builds a MediaItem per song, which is what originally
     * forced this to be suspend.)
     */
    suspend fun setShuffleEnabled(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)

    /**
     * Inserts [song] directly after the current item. Returns `false`, leaving the queue
     * untouched, when the engine is not connected to its playback session — the failure is
     * also reported on [errors] as [PlaybackError.ServiceUnavailable].
     */
    fun playNext(song: Song): Boolean

    /** Appends [song] to the end of the queue. Same `false` contract as [playNext]. */
    fun addToQueue(song: Song): Boolean

    /**
     * Moves the item identified by [slotId] to [toIndex] within [upcomingQueue], leaving
     * playback running. Honoured whether or not shuffle is enabled — ordering is app-managed
     * end to end, so a reorder while shuffled is a safe (if temporary, per spec) edit of the
     * live order — and honoured whether or not either position is currently loaded into the
     * player, since both are resolved against the full order rather than a timeline index.
     *
     * Identified by stable slot id (P4-T8 / R3-F04), like [removeQueueItem] — a position
     * captured by the UI at drag-start can be invalidated by any queue mutation landing before
     * the move actually executes (auto-advance, a deletion, another reorder), and acting on a
     * stale one moves the wrong song. Implementations must resolve [slotId]'s position fresh, at
     * call time, from their own live queue state, and reject the move outright if [slotId] no
     * longer exists there. [toIndex] remains a plain position — only the source needs identity.
     */
    fun moveQueueItem(slotId: String, toIndex: Int)

    /**
     * Removes the item with [slotId] from [upcomingQueue]. Safe under shuffle.
     *
     * Identified by stable slot id rather than by position. Removal is the destructive one:
     * a position captured by the UI can be invalidated by any queue mutation that lands
     * first, and acting on a stale one removes the wrong song permanently. Implementations
     * must resolve the position fresh, at call time, from their own live queue state.
     */
    fun removeQueueItem(slotId: String)

    /**
     * Jumps playback to the item identified by [slotId] in [upcomingQueue] and plays it.
     *
     * Identified by stable slot id (P4-T8 / R3-F04), not a display index — the same
     * argument [removeQueueItem]'s doc comment makes applies here just as much: a position
     * captured at tap time can name a different song by the time this actually executes.
     * Implementations must resolve [slotId] fresh, at call time, and reject the jump outright
     * (a no-op) if it no longer exists in [upcomingQueue].
     */
    fun seekToQueueItem(slotId: String)

    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()

    /**
     * The `onStart`/`onResume` recovery pass (P4-T2): re-verifies the whole cache against the
     * database regardless of whether anything has actually been missed — the general-purpose
     * backstop for a missed/interrupted event emission that a revision comparison alone cannot
     * catch (see [com.v1per.synthx.data.PlaybackCacheRevision]'s kdoc).
     */
    suspend fun reconcileWithDatabase()
}
