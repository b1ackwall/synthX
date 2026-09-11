package com.v1per.synthx.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide notice that a song row was deleted outright while its id may still be held in
 * memory elsewhere — currently just
 * [com.v1per.synthx.playback.ExoPlayerEngine.songsBySlotId] and the order store behind it,
 * neither of which has any other way to learn that a queued (or playing) song no longer exists
 * and, since complete delete landed, no longer has a file to play either.
 *
 * Deliberately a separate bus from [SongRemapEvents] rather than a second event type inside it:
 * a remap keeps every queue slot alive under a new content id, whereas this *removes* slots and
 * can move the playback cursor. The two consumers share no handling at all, so folding them into
 * one flow would only force every subscriber to branch on which kind of event arrived.
 *
 * Same singleton-bus rationale as [SongRemapEvents]: the emitting side
 * ([com.v1per.synthx.data.repository.SongRepositoryImpl.deleteSong], and the deletion half of
 * `replaceSong`) has no path back to whatever playback engine instance — if any — is currently
 * alive, and every live engine subscribes independently in its own `init`, so one emission
 * reaches all of them. No subscriber means events are simply dropped, which is correct: nothing
 * is playing, so there is no live state to correct.
 *
 * [BUFFER_CAPACITY] and [BufferOverflow.SUSPEND] mirror [SongRemapEvents] for the same reason —
 * every deletion notice is legitimate and none supersedes another, so silently dropping one
 * would reintroduce exactly the stale-queue bug this exists to prevent.
 */
object SongDeletionEvents {
    private const val BUFFER_CAPACITY = 64

    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Ids of songs whose row has just been deleted, emitted after the deleting transaction commits. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    suspend fun notifyDeleted(songId: String) {
        _events.emit(songId)
    }
}
