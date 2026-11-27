package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The song-tap and Play/Shuffle entry points of [PlaybackViewModel], with the Keep/Clear and Replace
 * prompts they raise before a fresh play throws a hand-built queue away. Holds no playback state of its own: every
 * read and every play goes through the view model's functions it is given.
 *
 * A raised prompt stays pending in [queuePrompt] or [queueReplacePrompt] until it is answered or
 * dismissed, so it survives the activity being recreated. Only one is pending at a time: raising
 * either takes the place of whatever is pending, and a fresh play ([clearPending]) closes both.
 * Answers act on the queue as it stands when they are given.
 *
 * While the saved-queue restore is pending and nothing is playing, a song tap or Play/Shuffle is
 * held ([holdFreshPlay]) and decided once the restore returns or its bound passes, from the engine's
 * own customised flag and playback state: a restored hand-built queue then raises its prompt exactly
 * as it would after the restore. Only the latest held tap or Play/Shuffle runs; nothing is shown
 * while it is held.
 *
 * Main-thread-confined, like the view model that owns it.
 */
internal class QueuePromptCoordinator(
    private val controller: PlaybackController,
    private val isQueueCustomized: () -> Boolean,
    private val hasActivePlayback: () -> Boolean,
    private val isNextInQueue: (songId: String) -> Boolean,
    private val playQueue: (songs: List<Song>, startIndex: Int, shuffle: Boolean?) -> Unit,
    private val holdWhilePending: (waitsForRestore: Boolean, action: suspend () -> Unit) -> Boolean,
    private val holdFreshPlay: (waitsForRestore: Boolean, action: suspend () -> Unit) -> Boolean,
    private val resume: () -> Unit,
) {
    private val _queuePrompt = MutableStateFlow<QueuePromptEvent?>(null)

    /** The pending Keep/Clear question of a song tap, or null. */
    val queuePrompt: StateFlow<QueuePromptEvent?> = _queuePrompt.asStateFlow()

    private val _queueReplacePrompt = MutableStateFlow<QueueReplacePromptEvent?>(null)

    /** The pending Replace question of a Play or Shuffle button, or null. */
    val queueReplacePrompt: StateFlow<QueueReplacePromptEvent?> = _queueReplacePrompt.asStateFlow()

    // Same customised-queue condition as requestPlayQueue, but the prompt only offers Replace:
    // there is no single tapped song for Keep to act on.
    // Shuffle's random start is already in [request]: it is chosen at tap time, held or not.
    fun requestPlayAll(request: PlayAllRequest?) {
        request ?: return
        if (heldForRestore { decidePlayAll(request, asksNow()) }) return
        decidePlayAll(request, isQueueCustomized() && hasActivePlayback())
    }

    private fun decidePlayAll(request: PlayAllRequest, asks: Boolean) {
        if (asks) {
            _queuePrompt.value = null
            _queueReplacePrompt.value = QueueReplacePromptEvent(request)
        } else {
            playQueue(request.songs, request.startIndex, request.shuffle)
        }
    }

    /**
     * Holds [decide] while the restore is pending and nothing is playing, dropping any earlier held
     * tap or Play/Shuffle; false when nothing is held and the caller decides now.
     */
    private fun heldForRestore(decide: suspend () -> Unit): Boolean = holdFreshPlay(!hasActivePlayback(), decide)

    /** The prompt condition after a wait, read from the engine itself: the view model copies can lag. */
    private suspend fun asksNow(): Boolean = controller.isQueueCustomizedNow() && controller.hasActivePlaybackNow()

    /**
     * The entry point every song tap goes through. Replacing a queue the user built by
     * hand is the one case where playing a song is destructive, so that case asks first
     * and everything else plays immediately.
     *
     * Also requires active playback: a customised queue that has since run to its end is
     * nothing to preserve, and "Keep" against a stopped player would insert into a dead
     * timeline. While the saved queue is restoring, the decision waits for it ([heldForRestore]).
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
        val event = QueuePromptEvent(tappedSong = tapped, contextSongs = filteredSongs, startIndex = filteredIndex)
        if (heldForRestore { decideTap(event, asksNow()) }) return
        decideTap(event, isQueueCustomized() && hasActivePlayback())
    }

    private fun decideTap(event: QueuePromptEvent, asks: Boolean) {
        if (asks) {
            _queueReplacePrompt.value = null
            _queuePrompt.value = event
        } else {
            playQueue(event.contextSongs, event.startIndex, null)
        }
    }

    /** Keep on the pending prompt, closing it; null (nothing to confirm) when none is pending. */
    fun answerKeep(): Boolean? = takeQueuePrompt()?.let(::keepQueueAndPlayNext)

    /** Clear on the pending prompt, closing it; nothing when none is pending. */
    fun answerClear() {
        takeQueuePrompt()?.let { clearQueueAndPlay(it.contextSongs, it.startIndex) }
    }

    fun dismissQueuePrompt() {
        _queuePrompt.value = null
    }

    /** Replace on the pending prompt, closing it: plays the request exactly as decided at tap time. */
    fun answerReplace() {
        val request = _queueReplacePrompt.value?.request ?: return
        _queueReplacePrompt.value = null
        playQueue(request.songs, request.startIndex, request.shuffle)
    }

    fun dismissQueueReplacePrompt() {
        _queueReplacePrompt.value = null
    }

    /** Closes both prompts: a fresh play puts a new queue in place of the one they ask about. */
    fun clearPending() {
        _queuePrompt.value = null
        _queueReplacePrompt.value = null
    }

    private fun takeQueuePrompt(): QueuePromptEvent? = _queuePrompt.value.also { _queuePrompt.value = null }

    /**
     * Keep: the hand-built queue stays, and [prompt]'s tapped song plays right after the current
     * slot, with the queued songs after it. Against active playback:
     *  - the song already exactly next (the first Up Next slot) plays from that slot, no copy;
     *  - otherwise a new copy goes right after the current slot (the Play Next insert, a Play Next
     *    extra while shuffled), also when the song is the current one or sits later in Up Next;
     *    nothing in between is skipped, and an earlier copy stays in history;
     *  - playback then moves to it and starts, even from a paused player.
     *
     * With no active playback (the queue ends while the prompt is open) there is nothing to keep:
     * the whole tapped list plays from the tapped song, exactly as Clear plays it. While the saved
     * queue is restoring or a queue load is still starting, the decision waits for them and then
     * applies these rules to the queue they leave.
     *
     * Returns true when it acted or is waiting, and null when the engine refused the insert for lack
     * of a session connection; the engine has already reported that on the errors flow.
     */
    fun keepQueueAndPlayNext(prompt: QueuePromptEvent): Boolean? {
        val song = prompt.tappedSong
        val active = hasActivePlayback()
        val held = holdWhilePending(!active) {
            if (!controller.keepAfterWait(song, resume)) clearQueueAndPlay(prompt.contextSongs, prompt.startIndex)
        }
        if (held) return true
        if (!active) {
            clearQueueAndPlay(prompt.contextSongs, prompt.startIndex)
            return true
        }
        if (!isNextInQueue(song.id) && !controller.playNext(song)) return null
        controller.next()
        resume()
        return true
    }

    /** Clear: the ordinary fresh-queue path, discarding whatever the user had queued. */
    fun clearQueueAndPlay(songs: List<Song>, startIndex: Int) {
        playQueue(songs, startIndex, null)
    }
}
