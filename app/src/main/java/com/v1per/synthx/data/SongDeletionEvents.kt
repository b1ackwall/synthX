package com.v1per.synthx.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One deletion notice. [revision] is this event's stamp from [PlaybackCacheRevision] — see that
 * object's kdoc (shared with [SongRemapEvents], P4-T1/P4-T2).
 */
data class SongDeletion(val songId: String, val revision: Long)

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
 * reaches all of them. `replay = 0` (the default, never overridden): a subscriber that starts
 * collecting after an emission sees nothing from before it — see [SongRemapEvents]' KDoc
 * correction, which applies identically here. [BUFFER_CAPACITY] only smooths a burst against an
 * already-subscribed slow collector.
 *
 * [BUFFER_CAPACITY] and [BufferOverflow.SUSPEND] mirror [SongRemapEvents] for the same reason —
 * every deletion notice is legitimate and none supersedes another, so silently dropping one
 * would reintroduce exactly the stale-queue bug this exists to prevent.
 */
object SongDeletionEvents {
    private const val BUFFER_CAPACITY = 64

    private val _events = MutableSharedFlow<SongDeletion>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Deletion notices, emitted after the deleting transaction commits. */
    val events: SharedFlow<SongDeletion> = _events.asSharedFlow()

    suspend fun notifyDeleted(songId: String) {
        _events.emit(SongDeletion(songId, PlaybackCacheRevision.next()))
    }
}

/**
 * The handoff barrier between "a deletion notice was emitted" and "playback has actually left the
 * file" (P4-T4 / R3-F03). `SongDeletionEvents.notifyDeleted`'s suspending `emit` only guarantees
 * the notice was enqueued into a buffered `SharedFlow` — not that any collector has run, nor that
 * the resulting controller commands (skip/stop/remove) have landed. Without this, the repository
 * proceeds straight to deleting the audio file, which can race a still-open file handle.
 *
 * Deliberately engine-count-aware rather than always waiting a fixed timeout: with no live
 * playback engine at all (the common case for a delete issued from a screen with nothing
 * playing), there is nothing to hand off to, and waiting anyway would delay every such delete for
 * no reason. [register] returns null in that case so the caller can skip the wait entirely.
 *
 * Not a per-song-id engine registry — engines don't report which songs they hold here, only
 * whether any exist at all. The registered waiter is completed by [signalCleared], which every
 * live engine calls once it has finished processing this exact deletion event (whether or not the
 * song was actually in that engine's queue) — see
 * [com.v1per.synthx.playback.ExoPlayerEngine]'s deletion collector.
 */
object SongDeletionAcknowledgment {
    private val liveEngineCount = AtomicInteger(0)
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Called once by a playback engine as it attaches (see `ExoPlayerEngine.init`). */
    fun engineAttached() {
        liveEngineCount.incrementAndGet()
    }

    /** Called once by a playback engine as it tears down (see `ExoPlayerEngine.releaseInternal`). */
    fun engineDetached() {
        liveEngineCount.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /**
     * Registers interest in [songId]'s handoff, or returns null when no live engine exists to
     * hand off to. Call right after emitting the deletion notice for [songId].
     */
    fun register(songId: String): CompletableDeferred<Unit>? {
        if (liveEngineCount.get() <= 0) return null
        return waiters.getOrPut(songId) { CompletableDeferred() }
    }

    /** Called by every live engine once it has finished reacting to [songId]'s deletion event. */
    fun signalCleared(songId: String) {
        waiters.remove(songId)?.complete(Unit)
    }
}
