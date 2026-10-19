package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QueueOrderStore] is the app's source of truth for the full play order, so these cover the
 * list algebra the whole windowed player rests on — including the cases that only matter because
 * the player does not hold the whole queue: an edit targeting a position thousands of songs
 * away from anything loaded has to be an ordinary, always-successful list operation.
 */
class QueueOrderStoreTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private class RecordingWriter : BaselineOrderWriter {
        val writes = mutableListOf<List<String>>()
        override fun writeBaselineOrder(records: List<QueueSlotRecord>) {
            writes += records.map { it.slotId }
        }
    }

    private fun store(writer: BaselineOrderWriter = RecordingWriter()) = QueueOrderStore(writer)

    /** Slot id and song id are deliberately distinct so a test can never conflate the two. */
    private fun records(vararg ids: String) = ids.map { QueueSlotRecord("slot-$it", "song-$it") }

    private fun bigQueue(count: Int) = (0 until count).map { QueueSlotRecord("slot-$it", "song-$it") }

    private fun QueueOrderStore.liveIds() = live.map { it.slotId }

    private fun QueueOrderStore.baselineIds() = baseline.map { it.slotId }

    @Test fun freshQueueReplacesBothOrdersAndPersistsBaseline() {
        val writer = RecordingWriter()
        val subject = store(writer)

        subject.captureFresh(records("a", "b", "c"))
        subject.captureFresh(records("x", "y"))

        assertEquals(listOf("slot-x", "slot-y"), subject.liveIds())
        assertEquals(listOf("slot-x", "slot-y"), subject.baselineIds())
        assertEquals(listOf("slot-x", "slot-y"), writer.writes.last())
    }

    /**
     * The atomic-write call sites (a fresh play, a shuffle toggle) pass `writeBaseline = false`
     * so their own narrow baseline write does not land independently of the unified transaction
     * they perform themselves — see `playQueueInternal` (ExoPlayerEngineQueueLoad.kt) and
     * `setShuffleEnabledInternal` (ExoPlayerEngineQueueOps.kt).
     */
    @Test fun captureFreshCanSuppressItsOwnBaselineWrite() {
        val writer = RecordingWriter()
        val subject = store(writer)

        subject.captureFresh(records("a", "b"), writeBaseline = false)

        assertEquals(listOf("slot-a", "slot-b"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b"), subject.baselineIds())
        assertTrue(writer.writes.isEmpty(), "writeBaseline=false must not trigger the writer")
    }

    @Test fun captureFreshShuffledSetsDifferentLiveAndBaselineOrders() {
        val writer = RecordingWriter()
        val subject = store(writer)
        val baseline = records("a", "b", "c")
        val live = listOf(baseline[1], baseline[0], baseline[2])

        subject.captureFreshShuffled(live, baseline, writeBaseline = false)

        assertEquals(listOf("slot-b", "slot-a", "slot-c"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b", "slot-c"), subject.baselineIds())
        assertTrue(writer.writes.isEmpty())
    }

    @Test fun unshuffleCanSuppressItsOwnBaselineWrite() {
        val writer = RecordingWriter()
        val subject = store(writer)
        subject.captureFresh(records("a", "b", "c"))
        subject.shuffle("slot-b") { it.reversed() }
        val writesBeforeUnshuffle = writer.writes.size

        assertTrue(subject.unshuffle("slot-b", writeBaseline = false))

        assertEquals(writesBeforeUnshuffle, writer.writes.size, "writeBaseline=false must not trigger the writer")
    }

    @Test fun restoreAdoptsBothOrdersWithoutRewritingBaseline() {
        val writer = RecordingWriter()
        val subject = store(writer)

        subject.restore(live = records("b", "a"), baseline = records("a", "b"))

        assertEquals(listOf("slot-b", "slot-a"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b"), subject.baselineIds())
        assertTrue(writer.writes.isEmpty())
    }

    /**
     * The Up Next list is fed from this, and it must be the whole remainder of the queue — not
     * the slice the player happens to have loaded. A 2000-song queue playing its first song owes
     * the UI 1999 rows.
     */
    @Test fun upcomingIsTheEntireRemainderNotAWindow() {
        val subject = store()
        subject.captureFresh(bigQueue(2000))

        val upcoming = subject.upcomingFrom("slot-0")

        assertEquals(1999, upcoming.size)
        assertEquals("slot-1", upcoming.first().slotId)
        assertEquals("slot-1999", upcoming.last().slotId)
        assertTrue(upcoming.size > WINDOW_SIZE)
    }

    @Test fun upcomingIsEmptyForAnUnknownSlot() {
        val subject = store()
        subject.captureFresh(records("a", "b"))

        assertEquals(emptyList(), subject.upcomingFrom("slot-missing"))
        assertEquals(emptyList(), subject.upcomingFrom(null))
    }

    @Test fun playNextWeavesBaselineOnlyWhileUnshuffled() {
        val unshuffled = store()
        unshuffled.captureFresh(records("a", "b", "c"))
        unshuffled.insertAfterCurrent(records("new").single(), "slot-a", mirrorToBaseline = true)
        assertEquals(listOf("slot-a", "slot-new", "slot-b", "slot-c"), unshuffled.liveIds())
        assertEquals(listOf("slot-a", "slot-new", "slot-b", "slot-c"), unshuffled.baselineIds())

        val shuffled = store()
        shuffled.captureFresh(records("a", "b", "c"))
        shuffled.insertAfterCurrent(records("new").single(), "slot-a", mirrorToBaseline = false)
        assertEquals(listOf("slot-a", "slot-new", "slot-b", "slot-c"), shuffled.liveIds())
        assertEquals(listOf("slot-a", "slot-b", "slot-c"), shuffled.baselineIds())
    }

    @Test fun addToQueueAppendsAndFollowsTheSameBaselineRule() {
        val subject = store()
        subject.captureFresh(records("a", "b"))

        subject.append(records("tail").single(), mirrorToBaseline = false)

        assertEquals(listOf("slot-a", "slot-b", "slot-tail"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b"), subject.baselineIds())
    }

    /**
     * The drag-reorder case windowing exists to make possible: both endpoints sit far outside
     * any window the player could have loaded, and the move still has to land exactly.
     */
    @Test fun moveResolvesPositionsFarOutsideAnyLoadedWindow() {
        val subject = store()
        subject.captureFresh(bigQueue(2000))

        // Row 1500 of Up Next (i.e. slot-1501 in the full order) dragged to the very top.
        val moved = subject.moveUpcoming(
            fromDisplayIndex = 1500,
            toDisplayIndex = 0,
            currentSlotId = "slot-0",
            mirrorToBaseline = true,
        )

        assertTrue(moved)
        assertEquals("slot-1501", subject.live[1].slotId)
        assertEquals("slot-1", subject.live[2].slotId)
        assertEquals(2000, subject.size)
        assertEquals(subject.liveIds(), subject.baselineIds())
    }

    @Test fun moveMirrorsBaselineOnlyWhileUnshuffled() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c", "d"))

        subject.moveUpcoming(0, 2, currentSlotId = "slot-a", mirrorToBaseline = false)

        assertEquals(listOf("slot-a", "slot-c", "slot-d", "slot-b"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b", "slot-c", "slot-d"), subject.baselineIds())
    }

    @Test fun moveRejectsOutOfRangeAndUnknownCurrentSlot() {
        val subject = store()
        subject.captureFresh(records("a", "b"))

        assertFalse(subject.moveUpcoming(0, 5, "slot-a", mirrorToBaseline = true))
        assertFalse(subject.moveUpcoming(0, 0, "slot-nope", mirrorToBaseline = true))
        assertEquals(listOf("slot-a", "slot-b"), subject.liveIds())
    }

    // --- slot-id-identified move -------------------------------------------

    /**
     * The moved item is resolved by identity, so it lands correctly
     * no matter what stale positional information the caller believed at drag-start.
     */
    @Test fun moveByIdResolvesTheCorrectItemRegardlessOfCallerAssumedPosition() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c", "d"))

        // "slot-c" is genuinely 2 positions into Up Next (after current "slot-a"); moved to
        // the very front — identical net effect to moveMirrorsBaselineOnlyWhileUnshuffled's
        // (0, 2) case, but requested by identity instead of a trusted index.
        assertTrue(subject.moveUpcoming("slot-c", toDisplayIndex = 0, currentSlotId = "slot-a", mirrorToBaseline = false))

        assertEquals(listOf("slot-a", "slot-c", "slot-b", "slot-d"), subject.liveIds())
    }

    /**
     * The drag-guard case: the slot SET is unchanged but the ORDER changed underneath the caller
     * (e.g. a reorder or shuffle landing between drag-start and drop). A caller-trusted
     * `fromDisplayIndex` captured before that reorder would name a different song; resolving by
     * slot id instead is immune to it.
     */
    @Test fun moveByIdIsImmuneToAnOrderChangeThatPreservesTheSameSlotSet() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c", "d"))
        // The order changes (b and c swap) but the slot SET is identical — exactly the gap a
        // UI-layer set-equality guard cannot detect.
        subject.moveUpcoming(fromDisplayIndex = 0, toDisplayIndex = 1, currentSlotId = "slot-a", mirrorToBaseline = false)
        assertEquals(listOf("slot-a", "slot-c", "slot-b", "slot-d"), subject.liveIds())

        // The user actually meant to move "slot-b" (wherever it now sits) to the front. Identity
        // resolution finds it at its NEW position (index 2), not wherever a stale caller-side
        // index would have assumed.
        assertTrue(subject.moveUpcoming("slot-b", toDisplayIndex = 0, currentSlotId = "slot-a", mirrorToBaseline = false))

        assertEquals(listOf("slot-a", "slot-b", "slot-c", "slot-d"), subject.liveIds())
    }

    /**
     * Deletion/transition landing between drag-start and drop: the dragged slot no longer
     * exists at all. Must reject cleanly — no move, never a fallback to some other occurrence.
     */
    @Test fun moveByIdRejectsCleanlyWhenTheSlotNoLongerExists() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c"))
        subject.remove("slot-b")

        assertFalse(subject.moveUpcoming("slot-b", toDisplayIndex = 0, currentSlotId = "slot-a", mirrorToBaseline = false))
        assertEquals(listOf("slot-a", "slot-c"), subject.liveIds())
    }

    @Test fun moveByIdRejectsTheCurrentlyPlayingSlotAndAnythingBeforeIt() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c"))

        // "slot-b" is current; "slot-a" sits before it in the full order — neither is a valid
        // Up Next move target relative to "slot-b".
        assertFalse(subject.moveUpcoming("slot-b", toDisplayIndex = 0, currentSlotId = "slot-b", mirrorToBaseline = false))
        assertFalse(subject.moveUpcoming("slot-a", toDisplayIndex = 0, currentSlotId = "slot-b", mirrorToBaseline = false))
        assertEquals(listOf("slot-a", "slot-b", "slot-c"), subject.liveIds())
    }

    @Test fun moveByIdMirrorsBaselineOnlyWhileUnshuffled() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c", "d"))

        subject.moveUpcoming("slot-b", toDisplayIndex = 2, currentSlotId = "slot-a", mirrorToBaseline = false)

        assertEquals(listOf("slot-a", "slot-c", "slot-d", "slot-b"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b", "slot-c", "slot-d"), subject.baselineIds())
    }

    /**
     * Mutation comparison for the slot-id move (same pattern as
     * `removeAllDeletesEveryDuplicateOccurrenceInExactlyOneBaselineWrite` below): the index-only
     * shape, exercised against a store that has already been reordered exactly like
     * [moveByIdIsImmuneToAnOrderChangeThatPreservesTheSameSlotSet], moves the WRONG song — so a
     * move that resolved by position would fail that test; the identity resolution is a genuine
     * behavior difference, not just a different way of asking for the same result.
     */
    @Test fun revertingToIndexOnlyResolutionMovesTheWrongSongAfterAReorder() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c", "d"))
        subject.moveUpcoming(fromDisplayIndex = 0, toDisplayIndex = 1, currentSlotId = "slot-a", mirrorToBaseline = false)
        assertEquals(listOf("slot-a", "slot-c", "slot-b", "slot-d"), subject.liveIds())

        // The caller still believes "slot-b" sits at display index 0 (its ORIGINAL position,
        // captured before the reorder above). Trusting that stale index moves "slot-c" — the
        // song now actually AT that position — instead of "slot-b".
        val staleFromDisplayIndex = 0
        subject.moveUpcoming(staleFromDisplayIndex, toDisplayIndex = 2, currentSlotId = "slot-a", mirrorToBaseline = false)

        // "slot-c" (wrong song) ended up moved to the end, while "slot-b" (the song the user
        // actually meant) never moved at all — exactly the bug the slot-id resolution prevents.
        assertEquals(listOf("slot-a", "slot-b", "slot-d", "slot-c"), subject.liveIds())
        assertTrue(subject.liveIds().contains("slot-b"), "slot-b never moved, but it's still present")
        assertEquals(1, subject.liveIds().indexOf("slot-b"), "slot-b stayed in place — the stale index acted on slot-c instead")
    }

    @Test fun removeStripsBothOrdersRegardlessOfShuffleState() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c"))
        subject.shuffle("slot-a") { it.reversed() }

        assertTrue(subject.remove("slot-b"))

        assertFalse(subject.liveIds().contains("slot-b"))
        assertFalse(subject.baselineIds().contains("slot-b"))
    }

    @Test fun removeIgnoresAnUnknownSlot() {
        val subject = store()
        subject.captureFresh(records("a"))

        assertFalse(subject.remove("slot-nope"))
        assertEquals(listOf("slot-a"), subject.liveIds())
    }

    /**
     * Deleting N duplicate occurrences of one song by calling [remove] once per occurrence
     * copies/filters the FULL live+baseline lists each time and, since every one of these
     * occurrences changes baseline too, triggers its own whole-baseline persistence write — N writes,
     * quadratic list work, for what should be a single edit. [removeAll] must do the same net
     * removal in exactly one baseline write.
     *
     * A per-slot `remove()` loop in `removeDeletedSongInternal` (the production call site) is
     * exactly the second assertion group below, run directly against this same store, so that
     * shape stays pinned without hand-editing production code.
     */
    /** Three independently addressable occurrences of the same song, plus two other songs. */
    private fun queueWithDuplicates() = listOf(
        QueueSlotRecord("slot-a", "song-a"),
        QueueSlotRecord("slot-dup-1", "song-dup"),
        QueueSlotRecord("slot-dup-2", "song-dup"),
        QueueSlotRecord("slot-dup-3", "song-dup"),
        QueueSlotRecord("slot-b", "song-b"),
    )

    @Test fun removeAllDeletesEveryDuplicateOccurrenceInExactlyOneBaselineWrite() {
        val batchWriter = RecordingWriter()
        val batchSubject = store(batchWriter)
        batchSubject.captureFresh(queueWithDuplicates())
        val duplicateSlotIds = batchSubject.live.filter { it.songId == "song-dup" }.map { it.slotId }
        val writesBeforeRemoval = batchWriter.writes.size // captureFresh's own write

        assertTrue(batchSubject.removeAll(duplicateSlotIds))

        assertEquals(listOf("slot-a", "slot-b"), batchSubject.liveIds())
        assertEquals(listOf("slot-a", "slot-b"), batchSubject.baselineIds())
        assertEquals(
            1,
            batchWriter.writes.size - writesBeforeRemoval,
            "removeAll must persist the baseline exactly once regardless of how many occurrences it removed",
        )

        // The mutation-test comparison: the per-slot-call shape, exercised against a fresh,
        // otherwise-identical store, produces the same end state but one baseline write PER
        // occurrence — proving removeAll's single-write behavior is a genuine difference and
        // not just a difference in how the assertion counts writes.
        val perSlotWriter = RecordingWriter()
        val perSlotSubject = store(perSlotWriter)
        perSlotSubject.captureFresh(queueWithDuplicates())
        val perSlotDuplicateIds = perSlotSubject.live.filter { it.songId == "song-dup" }.map { it.slotId }
        val perSlotWritesBeforeRemoval = perSlotWriter.writes.size

        perSlotDuplicateIds.forEach { slotId -> perSlotSubject.remove(slotId) }

        assertEquals(batchSubject.liveIds(), perSlotSubject.liveIds())
        assertEquals(batchSubject.baselineIds(), perSlotSubject.baselineIds())
        assertEquals(
            perSlotDuplicateIds.size,
            perSlotWriter.writes.size - perSlotWritesBeforeRemoval,
            "the per-occurrence loop this replaces really does write once per occurrence",
        )
        assertTrue(perSlotWriter.writes.size > batchWriter.writes.size)
    }

    @Test fun removeAllReturnsFalseWhenNothingMatches() {
        val subject = store()
        subject.captureFresh(records("a"))

        assertFalse(subject.removeAll(listOf("slot-nope", "slot-also-nope")))
        assertEquals(listOf("slot-a"), subject.liveIds())
    }

    @Test fun removeAllOnEmptyCollectionIsANoOp() {
        val writer = RecordingWriter()
        val subject = store(writer)
        subject.captureFresh(records("a"))

        assertFalse(subject.removeAll(emptyList()))
        assertTrue(writer.writes.isNotEmpty()) // only captureFresh's own write
        assertEquals(1, writer.writes.size)
    }

    /**
     * Slot identity, not song identity: the same song queued twice is two independently
     * removable occurrences, and that has to survive everything windowing added.
     */
    @Test fun duplicateOccurrencesOfOneSongStayIndependentlyAddressable() {
        val subject = store()
        subject.captureFresh(
            listOf(
                QueueSlotRecord("slot-1", "song-dup"),
                QueueSlotRecord("slot-2", "song-dup"),
                QueueSlotRecord("slot-3", "song-other"),
            )
        )

        assertTrue(subject.remove("slot-2"))

        assertEquals(listOf("slot-1", "slot-3"), subject.liveIds())
        assertEquals(0, subject.indexOfSlot("slot-1"))
        assertEquals(-1, subject.indexOfSlot("slot-2"))
    }

    @Test fun shuffleKeepsCurrentFirstPreservesMembershipAndLeavesBaselineAlone() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c", "d"))

        assertTrue(subject.shuffle("slot-c") { it.reversed() })

        assertEquals(listOf("slot-c", "slot-d", "slot-b", "slot-a"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b", "slot-c", "slot-d"), subject.baselineIds())
    }

    @Test fun shuffleReshufflesFromBaselineSoAlreadyPlayedSlotsComeBack() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c"))
        // Playing "c" with "a"/"b" already behind it: they are still baseline members, so an
        // ON toggle must bring them back into the live order rather than only reordering ahead.
        assertTrue(subject.shuffle("slot-c") { it })

        assertEquals(listOf("slot-c", "slot-a", "slot-b"), subject.liveIds())
    }

    @Test fun unshuffleRestoresBaselineTailAndFoldsInSessionAdditions() {
        val subject = store()
        subject.captureFresh(records("a", "b", "c", "d"))
        subject.shuffle("slot-b") { it.reversed() }
        // Added while shuffled, so deliberately not woven into baseline at the time.
        subject.append(records("extra").single(), mirrorToBaseline = false)

        assertTrue(subject.unshuffle("slot-b"))

        // Current first, then baseline's tail after it, then the shuffle-session addition.
        assertEquals(listOf("slot-b", "slot-c", "slot-d", "slot-extra"), subject.liveIds())
        // History before the current slot is dropped permanently, from baseline too.
        assertEquals(subject.liveIds(), subject.baselineIds())
        assertFalse(subject.baselineIds().contains("slot-a"))
    }

    @Test fun shuffleAndUnshuffleRejectAnUnknownCurrentSlot() {
        val subject = store()
        subject.captureFresh(records("a"))

        assertFalse(subject.shuffle("slot-nope"))
        assertFalse(subject.unshuffle("slot-nope"))
        assertFalse(subject.shuffle(null))
    }

    @Test fun remapSongRewritesContentIdsAcrossBothOrdersKeepingSlotIds() {
        val subject = store()
        subject.captureFresh(
            listOf(
                QueueSlotRecord("slot-1", "song-old"),
                QueueSlotRecord("slot-2", "song-old"),
                QueueSlotRecord("slot-3", "song-keep"),
            )
        )

        assertTrue(subject.remapSong("song-old", "song-new"))

        assertEquals(listOf("slot-1", "slot-2", "slot-3"), subject.liveIds())
        assertEquals(listOf("song-new", "song-new", "song-keep"), subject.live.map { it.songId })
        assertEquals(listOf("song-new", "song-new", "song-keep"), subject.baseline.map { it.songId })
        assertFalse(subject.remapSong("song-absent", "song-whatever"))
    }

    /**
     * Graduation ("Remove from Starred") emits a [com.v1per.synthx.data.SongRemapEvents] remap
     * whose new id equals the old one — it moves the file and rewrites `file_path` while the
     * content hash the id derives from is unchanged. The engine's remap handler calls through to
     * [QueueOrderStore.remapSong] unconditionally, so that same-id call has to be an inert
     * no-op: every slot key and song id untouched, and — since nothing changed — no baseline
     * persist triggered.
     */
    @Test fun remapSongToTheSameIdChangesNothingAndPersistsNothing() {
        val writer = RecordingWriter()
        val subject = store(writer)
        subject.captureFresh(
            listOf(
                QueueSlotRecord("slot-1", "song-same"),
                QueueSlotRecord("slot-2", "song-same"),
                QueueSlotRecord("slot-3", "song-other"),
            )
        )
        val writesAfterCapture = writer.writes.size

        assertFalse(subject.remapSong("song-same", "song-same"))

        assertEquals(listOf("slot-1", "slot-2", "slot-3"), subject.liveIds())
        assertEquals(listOf("slot-1", "slot-2", "slot-3"), subject.baselineIds())
        assertEquals(listOf("song-same", "song-same", "song-other"), subject.live.map { it.songId })
        assertEquals(
            listOf("song-same", "song-same", "song-other"),
            subject.baseline.map { it.songId },
        )
        assertEquals(writesAfterCapture, writer.writes.size)
    }
}
