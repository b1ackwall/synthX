package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song

/**
 * Asks the user what to do with a hand-modified queue before it gets replaced.
 *
 * Carries both readings of the tap, because the two answers need different ones: "Keep"
 * only ever acts on [tappedSong], while "Clear" rebuilds the queue from [contextSongs] at
 * [startIndex]. Keeping [tappedSong] as its own field rather than making the UI index back
 * into the list is what stops the two from drifting apart.
 */
data class QueuePromptEvent(val tappedSong: Song, val contextSongs: List<Song>, val startIndex: Int)

/** Outcome of [PlaybackViewModel.playNext]/[PlaybackViewModel.addToQueue]. */
sealed class QueueAddResult {
    /** The controller call went through. */
    data object Added : QueueAddResult()

    /** The song is already in the position ([playNext]'s next slot, [addToQueue]'s last
     *  slot) that would make this call a likely-accidental duplicate — the caller should
     *  ask the user to confirm, then retry with `force = true`. */
    data object NeedsConfirmation : QueueAddResult()

    /** The engine refused the insert because it is not connected to the playback session; it
     *  has already reported that on [PlaybackViewModel.errors], so callers show nothing more. */
    data object Unavailable : QueueAddResult()
}
