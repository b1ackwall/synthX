package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The song a pending duplicate confirmation of the song-actions menu asks about: [playNext] for
 * "already playing next, play it next again?" and [addToQueue] for "already at the end, add another
 * copy?". Held by [PlaybackViewModel] rather than the composition, so a pending question survives
 * the activity being recreated. The host asks after a [QueueAddResult.NeedsConfirmation], takes the
 * song on confirm (a second confirm then finds nothing) and dismisses on cancel or Back.
 *
 * Main-thread-confined, like the view model that owns it.
 */
class QueueDuplicateConfirmations {
    private val _playNext = MutableStateFlow<Song?>(null)
    val playNext: StateFlow<Song?> = _playNext.asStateFlow()

    private val _addToQueue = MutableStateFlow<Song?>(null)
    val addToQueue: StateFlow<Song?> = _addToQueue.asStateFlow()

    fun askPlayNext(song: Song) {
        _playNext.value = song
    }

    /** The song the Play Next question asks about, closing the question; null when none is pending. */
    fun takePlayNext(): Song? = _playNext.value.also { _playNext.value = null }

    fun dismissPlayNext() {
        _playNext.value = null
    }

    fun askAddToQueue(song: Song) {
        _addToQueue.value = song
    }

    /** The song the Add to Queue question asks about, closing the question; null when none is pending. */
    fun takeAddToQueue(): Song? = _addToQueue.value.also { _addToQueue.value = null }

    fun dismissAddToQueue() {
        _addToQueue.value = null
    }
}
