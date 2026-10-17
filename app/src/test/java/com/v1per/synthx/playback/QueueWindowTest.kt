package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The windowing logic that keeps at most [WINDOW_SIZE] `MediaItem`s live in the player no matter
 * how large the real queue is: which slice should be loaded ([computeWindow]), how to get there
 * from what is loaded ([computeWindowEdits]), when it is worth doing ([windowNeedsRefill]), and
 * the repeat-aware "what plays next" rule ([nextIndexInOrder]) that three of the five windowing
 * correctness traps reduce to.
 *
 * All four are pure, so every case here is exact rather than a smoke test.
 */
class QueueWindowTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private fun order(count: Int) = (0 until count).map { QueueSlotRecord("slot-$it", "song-$it") }

    private fun List<QueueSlotRecord>.ids() = map { it.slotId }

    /**
     * Mirrors media3's playlist semantics so an edit list can be checked by actually running it:
     * `removeMediaItems` takes a half-open range, `addMediaItems` inserts at an index, and
     * `moveMediaItem(from, to)` removes then re-inserts.
     */
    private fun applyEdits(loaded: List<String>, edits: List<WindowEdit>): List<String> {
        val timeline = loaded.toMutableList()
        for (edit in edits) {
            when (edit) {
                is WindowEdit.Remove ->
                    repeat(edit.toIndexExclusive - edit.fromIndex) { timeline.removeAt(edit.fromIndex) }
                is WindowEdit.Insert ->
                    timeline.addAll(edit.index, edit.records.map { it.slotId })
                is WindowEdit.Move ->
                    timeline.add(edit.toIndex, timeline.removeAt(edit.fromIndex))
            }
        }
        return timeline
    }

    /** Runs the edits one at a time, asserting [slotId] is present after every single step. */
    private fun assertNeverDisturbed(
        loaded: List<String>,
        edits: List<WindowEdit>,
        slotId: String,
    ) {
        assertTrue(slotId in loaded, "precondition: $slotId should start out loaded")
        edits.forEachIndexed { index, edit ->
            val afterStep = applyEdits(loaded, edits.take(index + 1))
            assertTrue(slotId in afterStep, "$slotId was dropped by edit #$index ($edit)")
        }
    }

    // --- computeWindow -----------------------------------------------------------------

    @Test fun aQueueThatFitsIsLoadedWholeAndNeverRotated() {
        val small = order(WINDOW_SIZE)

        assertEquals(small.ids(), computeWindow(small, currentIndex = 0, wrapAround = false).ids())
        // Even with repeat-all: the whole queue is loaded, so media3's own wrap is already right
        // and rotating it would churn the timeline on every transition for no reason.
        assertEquals(small.ids(), computeWindow(small, currentIndex = 40, wrapAround = true).ids())
    }

    @Test fun midQueueLoadsExactlyBackBeforeAndForwardAfter() {
        val window = computeWindow(order(2000), currentIndex = 900, wrapAround = false)

        assertEquals(WINDOW_SIZE, window.size)
        assertEquals("slot-${900 - WINDOW_BACK}", window.first().slotId)
        assertEquals("slot-${900 + WINDOW_FORWARD}", window.last().slotId)
        assertEquals(WINDOW_BACK, window.indexOfFirst { it.slotId == "slot-900" })
    }

    @Test fun nearTheEndsTheWindowClampsWithoutRepeatAll() {
        val large = order(2000)

        val atStart = computeWindow(large, currentIndex = 3, wrapAround = false)
        assertEquals("slot-0", atStart.first().slotId)
        assertEquals("slot-43", atStart.last().slotId)

        val atEnd = computeWindow(large, currentIndex = 1999, wrapAround = false)
        assertEquals("slot-1989", atEnd.first().slotId)
        assertEquals("slot-1999", atEnd.last().slotId)
    }

    /**
     * Repeat-all wraparound, the locked requirement: near the true end of a queue too large to
     * fit, the loaded window has to continue into the true *start* of the queue, so the wrap
     * happens around the real queue rather than around the window.
     */
    @Test fun repeatAllWrapsTheWindowToTheTrueStartOfTheQueue() {
        val large = order(2000)

        val window = computeWindow(large, currentIndex = 1995, wrapAround = true)

        assertEquals(WINDOW_SIZE, window.size)
        assertEquals("slot-1985", window.first().slotId)
        // ... 1985..1999 then straight into 0..35, in play order.
        assertEquals("slot-1999", window[WINDOW_BACK + 4].slotId)
        assertEquals("slot-0", window[WINDOW_BACK + 5].slotId)
        assertEquals("slot-35", window.last().slotId)
    }

    @Test fun repeatAllAlsoWrapsBackwardsSoTheQueueHeadHasHistory() {
        val window = computeWindow(order(2000), currentIndex = 2, wrapAround = true)

        assertEquals("slot-1992", window.first().slotId)
        assertEquals("slot-0", window[WINDOW_BACK - 2].slotId)
        assertEquals("slot-42", window.last().slotId)
    }

    @Test fun aWrappedWindowNeverLoadsTheSameSlotTwice() {
        // Duplicate media ids would break the slot-id correlation the whole design rests on.
        for (count in listOf(WINDOW_SIZE + 1, WINDOW_SIZE + 7, 300, 2000)) {
            for (current in listOf(0, 1, count / 2, count - 2, count - 1)) {
                val window = computeWindow(order(count), current, wrapAround = true)
                assertEquals(
                    window.size,
                    window.ids().toSet().size,
                    "duplicate slot in window for count=$count current=$current",
                )
            }
        }
    }

    @Test fun anUnresolvableAnchorFallsBackToTheHeadOfTheQueue() {
        val window = computeWindow(order(2000), currentIndex = -1, wrapAround = false)

        assertEquals(WINDOW_SIZE, window.size)
        assertEquals("slot-0", window.first().slotId)
        assertEquals(emptyList(), computeWindow(emptyList(), 0, wrapAround = true))
    }

    // --- computeWindowEdits ------------------------------------------------------------

    @Test fun anAlreadyCorrectWindowProducesNoEdits() {
        val desired = computeWindow(order(2000), currentIndex = 900, wrapAround = false)

        assertEquals(emptyList(), computeWindowEdits(desired.ids(), desired))
    }

    /**
     * The steady state: the window has slid forward, so reconciliation should be one contiguous
     * drop off the back and one contiguous batch onto the front — not one call per item, which
     * is the O(N-per-call) cost that made a full-timeline reorder quadratic.
     */
    @Test fun aSlidWindowCostsExactlyOneRemoveAndOneInsert() {
        val large = order(2000)
        val loaded = computeWindow(large, currentIndex = 900, wrapAround = false).ids()
        val desired = computeWindow(large, currentIndex = 920, wrapAround = false)

        val edits = computeWindowEdits(loaded, desired)

        assertEquals(1, edits.count { it is WindowEdit.Remove })
        assertEquals(1, edits.count { it is WindowEdit.Insert })
        assertEquals(0, edits.count { it is WindowEdit.Move })
        assertEquals(desired.ids(), applyEdits(loaded, edits))
        assertNeverDisturbed(loaded, edits, "slot-920")
    }

    @Test fun everyReconciliationScenarioActuallyReachesTheDesiredWindow() {
        val large = order(2000)
        val scenarios = listOf(
            // fresh advance, backward scrub, jump forward, near-end clamp, wrap-around
            900 to 901,
            900 to 880,
            900 to 1400,
            1000 to 1990,
            900 to 40,
        )
        for ((from, to) in scenarios) {
            for (wrap in listOf(false, true)) {
                val loaded = computeWindow(large, from, wrapAround = wrap).ids()
                val desired = computeWindow(large, to, wrapAround = wrap)
                assertEquals(
                    desired.ids(),
                    applyEdits(loaded, computeWindowEdits(loaded, desired)),
                    "reconciling $from -> $to (wrap=$wrap)",
                )
            }
        }
    }

    /**
     * A drag-reorder inside the loaded window changes order without changing membership, which
     * removal/insertion alone cannot express — and the playing item must survive it untouched.
     */
    @Test fun aReorderInsideTheWindowIsResolvedWithMovesAndKeepsPlaybackAlive() {
        val large = order(2000).toMutableList()
        val loaded = computeWindow(large, currentIndex = 900, wrapAround = false).ids()
        // Drag slot-930 to sit immediately after the playing item.
        large.add(901, large.removeAt(930))
        val desired = computeWindow(large, currentIndex = 900, wrapAround = false)

        val edits = computeWindowEdits(loaded, desired)

        assertTrue(edits.any { it is WindowEdit.Move })
        assertEquals(desired.ids(), applyEdits(loaded, edits))
        assertNeverDisturbed(loaded, edits, "slot-900")
    }

    /**
     * A shuffle toggle replaces almost the whole window. The point of doing it through
     * reconciliation rather than `setMediaItems` is that the playing item is never torn down —
     * which is exactly what used to cause the audible rebuffer on a large queue.
     */
    @Test fun aWholesaleShuffleKeepsThePlayingItemLoadedThroughout() {
        val store = QueueOrderStore { }
        store.captureFresh(order(2000))
        val loaded = computeWindow(store.live, currentIndex = 900, wrapAround = false).ids()

        store.shuffle("slot-900") { it.reversed() }
        val desired = computeWindow(store.live, currentIndex = 0, wrapAround = false)

        val edits = computeWindowEdits(loaded, desired)

        assertEquals("slot-900", desired.first().slotId)
        assertEquals(desired.ids(), applyEdits(loaded, edits))
        assertNeverDisturbed(loaded, edits, "slot-900")
    }

    @Test fun playNextInsertsOneItemWithoutTouchingAnythingElse() {
        val large = order(2000).toMutableList()
        val loaded = computeWindow(large, currentIndex = 900, wrapAround = false).ids()
        large.add(901, QueueSlotRecord("slot-inserted", "song-inserted"))
        val desired = computeWindow(large, currentIndex = 900, wrapAround = false)

        val edits = computeWindowEdits(loaded, desired)

        assertEquals(desired.ids(), applyEdits(loaded, edits))
        assertNeverDisturbed(loaded, edits, "slot-900")
        assertTrue(edits.filterIsInstance<WindowEdit.Insert>().any { insert ->
            insert.records.any { it.slotId == "slot-inserted" }
        })
    }

    @Test fun anEmptyTimelineIsFilledByASingleInsert() {
        val desired = computeWindow(order(2000), currentIndex = 0, wrapAround = false)

        val edits = computeWindowEdits(emptyList(), desired)

        assertEquals(1, edits.size)
        assertEquals(desired.ids(), applyEdits(emptyList(), edits))
    }

    // --- windowNeedsRefill -------------------------------------------------------------

    @Test fun aFreshWindowIsNotRefilledOnEveryTransition() {
        val large = order(2000)
        val loaded = computeWindow(large, currentIndex = 900, wrapAround = false).ids()

        // Playing item still has full lookahead: nothing to do.
        assertFalse(windowNeedsRefill(loaded, computeWindow(large, 900, false), "slot-900"))
        // A few tracks later, still comfortably above the hysteresis threshold.
        assertFalse(windowNeedsRefill(loaded, computeWindow(large, 910, false), "slot-910"))
    }

    @Test fun lookaheadDecayingPastTheThresholdTriggersARefill() {
        val large = order(2000)
        val loaded = computeWindow(large, currentIndex = 900, wrapAround = false).ids()
        val justAbove = 900 + WINDOW_FORWARD - WINDOW_REFILL_HEADROOM
        val justBelow = justAbove + 1

        assertFalse(
            windowNeedsRefill(loaded, computeWindow(large, justAbove, false), "slot-$justAbove"),
        )
        assertTrue(
            windowNeedsRefill(loaded, computeWindow(large, justBelow, false), "slot-$justBelow"),
        )
    }

    /**
     * At the true end of the queue with repeat off there is nothing left to load, so decaying
     * lookahead must not make this thrash a pointless reconciliation on every transition.
     */
    @Test fun theTrueEndOfTheQueueStopsAskingForMore() {
        val large = order(2000)
        val loaded = computeWindow(large, currentIndex = 1999, wrapAround = false).ids()

        assertFalse(windowNeedsRefill(loaded, computeWindow(large, 1999, false), "slot-1999"))
    }

    @Test fun aCurrentSlotThatIsNotLoadedAlwaysForcesAReconciliation() {
        val large = order(2000)
        val loaded = computeWindow(large, currentIndex = 900, wrapAround = false).ids()

        assertTrue(windowNeedsRefill(loaded, computeWindow(large, 1500, false), "slot-1500"))
    }

    // --- nextIndexInOrder (correctness traps 1, 2, 3 and 4) ----------------------------

    /**
     * The trap this exists for: the loaded window ends ~[WINDOW_FORWARD] ahead of the playing
     * song, so asking the player "is there a next item" answers no while hundreds of songs are
     * still queued — which would blank the skip button and, worse, stop playback outright from
     * the STATE_ENDED handler. Asked of the full order, the answer is simply the next index.
     */
    @Test fun thereIsAlwaysANextSongWhileTheRealQueueHasMoreLeft() {
        assertEquals(901, nextIndexInOrder(count = 2000, currentIndex = 900, RepeatMode.Off))
        // The exact position an exhausted window would leave the cursor at: last loaded item.
        val lastLoaded = 900 + WINDOW_FORWARD
        assertEquals(lastLoaded + 1, nextIndexInOrder(2000, lastLoaded, RepeatMode.Off))
    }

    @Test fun onlyAGenuinelyExhaustedQueueHasNoNextSong() {
        assertNull(nextIndexInOrder(count = 2000, currentIndex = 1999, RepeatMode.Off))
        assertNull(nextIndexInOrder(count = 0, currentIndex = -1, RepeatMode.Off))
        assertNull(nextIndexInOrder(count = 5, currentIndex = -1, RepeatMode.All))
        assertNull(nextIndexInOrder(count = 5, currentIndex = 9, RepeatMode.All))
    }

    @Test fun repeatAllWrapsAtTheTrueEndOfTheQueue() {
        assertEquals(0, nextIndexInOrder(count = 2000, currentIndex = 1999, RepeatMode.All))
        assertEquals(1500, nextIndexInOrder(count = 2000, currentIndex = 1499, RepeatMode.All))
    }

    @Test fun repeatOneStillOffersADifferentSongToSkipOnto() {
        assertEquals(0, nextIndexInOrder(count = 2000, currentIndex = 1999, RepeatMode.One))
        assertEquals(901, nextIndexInOrder(count = 2000, currentIndex = 900, RepeatMode.One))
    }
}
