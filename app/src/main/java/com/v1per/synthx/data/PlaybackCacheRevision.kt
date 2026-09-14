package com.v1per.synthx.data

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide monotonic counter stamped onto every [SongRemapEvents]/[SongDeletionEvents]
 * emission (P4-T1/P4-T2 — one shared revision scheme for both buses, per their combined design).
 *
 * Deliberately a single global counter rather than a per-song or per-bus one: the bug this exists
 * to close is cross-bus ordering (a scan's remap and a deletion's notice racing against each
 * other and against a consumer's own suspended work), not "did this one song change" — a
 * per-song scheme could not tell a consumer "something happened while you were suspended, but not
 * to a song you were tracking yet" the way a global one trivially can. A consumer that has no
 * matching cached slot for either event still needs to know its *snapshot itself* might already be
 * stale by the time it finishes installing, which is exactly what capturing [current] before a
 * suspension and comparing it against [current] again afterward answers, independent of which
 * song(s) actually changed.
 *
 * This intentionally does NOT attempt to be a tombstone registry (remembering exactly which ids
 * were deleted/remapped and when). A bumped revision only ever means "go re-verify your own cache
 * against the database" — the consumer re-derives the truth itself rather than trusting a second
 * copy of it riding along on the event. That is simpler than threading a full history of what
 * changed through two independent `SharedFlow`s, and closes the same failing interleaving a
 * tombstone would: any consumer that captures a revision before suspending and re-checks the
 * database after installing can never be fooled by an event it "correctly" ignored as irrelevant
 * against a stale cache, because the revision bump alone is enough to trigger the re-verification
 * pass regardless of whether the ignored event would have mattered.
 */
object PlaybackCacheRevision {
    private val counter = AtomicLong(0L)

    /** Stamped onto an event at the moment it is published. Never reused, never decreases. */
    fun next(): Long = counter.incrementAndGet()

    /** A consumer's snapshot of "nothing has happened yet" to compare against later. */
    fun current(): Long = counter.get()
}
