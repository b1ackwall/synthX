package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The song-tap and Play/Shuffle entry points of [PlaybackViewModel], with the Keep/Clear and Replace
 * prompts they raise before a hand-built queue is replaced. Holds no playback state of its own: every
 * read and every play goes through the view model's functions it is given.
 *
 * Main-thread-confined, like the view model that owns it.
 */
internal class QueuePromptCoordinator(
    private val controller: PlaybackController,
    private val isQueueCustomized: () -> Boolean,
    private val hasActivePlayback: () -> Boolean,
    private val isAlreadyQueued: (songId: String) -> Boolean,
    private val playQueue: (songs: List<Song>, startIndex: Int, shuffle: Boolean?) -> Unit,
    private val playAloneUnlessRestored: (song: Song, applied: suspend () -> Boolean) -> Unit,
) {
    // Same one-off event shape as errors and SongActionsViewModel.events: buffered
    // by one, newest wins, never replayed — a prompt the user already answered must not
    // reappear on the next collector.
    private val _queuePrompts = MutableSharedFlow<QueuePromptEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val queuePrompts: SharedFlow<QueuePromptEvent> = _queuePrompts.asSharedFlow()

    // Play/Shuffle's counterpart of queuePrompts, same one-off shape.
    private val _queueReplacePrompts = MutableSharedFlow<QueueReplacePromptEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val queueReplacePrompts: SharedFlow<QueueReplacePromptEvent> = _queueReplacePrompts.asSharedFlow()

    // Same customised-queue condition as requestPlayQueue, but the prompt only offers Replace:
    // there is no single tapped song for Keep to act on.
    fun requestPlayAll(request: PlayAllRequest?) {
        request ?: return
        if (isQueueCustomized() && hasActivePlayback()) {
            _queueReplacePrompts.tryEmit(QueueReplacePromptEvent(request))
        } else {
            playQueue(request.songs, request.startIndex, request.shuffle)
        }
    }

    /**
     * The entry point every song tap goes through. Replacing a queue the user built by
     * hand is the one case where playing a song is destructive, so that case asks first
     * and everything else plays immediately.
     *
     * Also requires active playback: a customised queue that has since run to its end is
     * nothing to preserve, and "Keep" against a stopped player would insert into a dead
     * timeline.
     */
    fun requestPlayQueue(songs: List<Song>, startIndex: Int) {
        // The incoming list is whatever a screen displays, which can include missing songs
        // mixed in among playable ones (SongRow disables tapping them, but leaves them in
        // the list so the row still renders dimmed) — strip them here, once, upstream of
        // every screen's tap handler, so no missing song ever reaches the engine as a
        // MediaItem. The tapped song is identified by id *before* filtering and re-located
        // in the filtered list, since filtering can shift its numeric position.
        val tapped = songs.getOrNull(startIndex) ?: return
        if (tapped.isMissing) return
        val filteredSongs = songs.filterNot { it.isMissing }
        val filteredIndex = filteredSongs.indexOfFirst { it.id == tapped.id }
        if (filteredIndex < 0) return
        if (isQueueCustomized() && hasActivePlayback()) {
            _queuePrompts.tryEmit(
                QueuePromptEvent(
                    tappedSong = tapped,
                    contextSongs = filteredSongs,
                    startIndex = filteredIndex,
                ),
            )
        } else {
            playQueue(filteredSongs, filteredIndex, null)
        }
    }

    /**
     * Keep: the customised queue survives untouched, the tapped song is spliced in right
     * after the current item and jumped to, so what the user queued up plays after it. The
     * list the song came from is deliberately discarded — only the song matters here.
     *
     * Uses the blanket [isAlreadyQueued] dedup, not the position-specific check
     * Play Next and Add to Queue use — this flow answers a queue-replace prompt rather than an
     * ordinary tap, so it keeps the coarser "anywhere in the queue" guard.
     * Returns `false` without touching the controller when [song] is already the current
     * song or already sits in Up Next. Dedup only applies once there's an existing
     * queue to be a duplicate of, matching the active-playback gating on the other two
     * methods.
     *
     * Returns `null` when the engine refused the insert for lack of a session connection
     * — it has already reported that on the errors flow, so the caller shows nothing.
     */
    fun keepQueueAndPlayNext(song: Song): Boolean? {
        // The queue can have ended between the prompt being raised and answered.
        if (!hasActivePlayback()) {
            playAloneUnlessRestored(song) { controller.keepAfterRestore(song) }
            return true
        }
        if (isAlreadyQueued(song.id)) return false
        if (!controller.playNext(song)) return null
        controller.next()
        return true
    }

    /** Clear: the ordinary fresh-queue path, discarding whatever the user had queued. */
    fun clearQueueAndPlay(songs: List<Song>, startIndex: Int) {
        playQueue(songs, startIndex, null)
    }
}
