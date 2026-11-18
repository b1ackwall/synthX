package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shuffle rules that need more than the Repeat All off examples in [QueueShuffleExamplesTest]:
 * the Repeat All and Repeat One variants, the session extras and their tags, the reorder flag,
 * the fail-closed guard and the fresh-play shuffle.
 */
class QueueShuffleRulesTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val reversed: (List<QueueSlotRecord>) -> List<QueueSlotRecord> = { it.reversed() }

    private fun t(index: Int) = QueueSlotRecord("t$index", "song-t$index")

    private fun album(count: Int) = (1..count).map(::t)

    private fun extra(id: String) = QueueSlotRecord(id, "song-$id")

    private fun ids(range: IntProgression) = range.map { "t$it" }

    private fun storeWith(records: List<QueueSlotRecord>) = QueueOrderStore { }.apply { captureFresh(records) }

    private fun QueueOrderStore.liveIds() = live.map { it.slotId }

    private fun QueueOrderStore.baselineIds() = baseline.map { it.slotId }

    // --- Repeat All ------------------------------------------------------------------------------

    @Test fun e6RepeatAllShufflesTheHistoryInAndShuffleOffWrapsAfterTheCurrentSong() {
        val store = storeWith(album(8))

        assertTrue(store.shuffle("t4", RepeatMode.All, shuffler = reversed))
        assertEquals(listOf("t4", "t8", "t7", "t6", "t5", "t3", "t2", "t1"), store.liveIds())
        assertEquals(ids(1..8), store.baselineIds())

        assertTrue(store.unshuffle("t4", RepeatMode.All))
        assertEquals(listOf("t4", "t5", "t6", "t7", "t8", "t1", "t2", "t3"), store.liveIds())
        assertEquals(store.liveIds(), store.baselineIds())
    }

    @Test fun repeatAllShuffleOffKeepsHeardExtrasBeforeTheCurrentSong() {
        val store = storeWith(album(6))
        store.shuffle("t1", RepeatMode.All, shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        assertEquals(listOf("t1", "X", "t6"), store.liveIds().take(3))

        // X plays, then t6.
        assertTrue(store.unshuffle("t6", RepeatMode.All))

        assertEquals(listOf("X", "t6", "t1", "t2", "t3", "t4", "t5"), store.liveIds())
    }

    @Test fun repeatAllShuffleOffFromAnExtraCurrentSongWrapsBackToTheAnchorSong() {
        val store = storeWith(album(5))
        store.shuffle("t2", RepeatMode.All, shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t2", mirrorToBaseline = false)

        // X plays: t2 is the album song it follows.
        assertTrue(store.unshuffle("X", RepeatMode.All))

        assertEquals(listOf("X", "t3", "t4", "t5", "t1", "t2"), store.liveIds())
    }

    @Test fun repeatAllShuffleOffPlacesPlayNextFirstAndAddToQueueLast() {
        val store = storeWith(album(5))
        store.shuffle("t3", RepeatMode.All, shuffler = reversed)
        store.append(extra("Y"), mirrorToBaseline = false)
        store.insertAfterCurrent(extra("X"), "t3", mirrorToBaseline = false)

        assertTrue(store.unshuffle("t3", RepeatMode.All))

        assertEquals(listOf("t3", "X", "t4", "t5", "t1", "t2", "Y"), store.liveIds())
    }

    // --- Repeat One --------------------------------------------------------------------------

    @Test fun repeatOneFollowsTheRepeatOffRulesForShuffleOnAndOff() {
        val one = storeWith(album(6))
        val off = storeWith(album(6))

        assertTrue(one.shuffle("t3", RepeatMode.One, shuffler = reversed))
        assertTrue(off.shuffle("t3", RepeatMode.Off, shuffler = reversed))
        assertEquals(listOf("t1", "t2", "t3", "t6", "t5", "t4"), one.liveIds(), "history is not shuffled in")
        assertEquals(off.liveIds(), one.liveIds())

        one.insertAfterCurrent(extra("X"), "t3", mirrorToBaseline = false)
        off.insertAfterCurrent(extra("X"), "t3", mirrorToBaseline = false)
        assertTrue(one.unshuffle("t6", RepeatMode.One))
        assertTrue(off.unshuffle("t6", RepeatMode.Off))
        assertEquals(listOf("t1", "t2", "t3", "X", "t6", "t4", "t5"), one.liveIds(), "history is not replayed")
        assertEquals(off.liveIds(), one.liveIds())
    }

    // --- song-row tap and fresh plays --------------------------------------------------------

    @Test fun e14ASongTapWhileShuffledStartsOnTheTappedSongWithTheListAsBaseline() {
        val list = album(10)
        val live = freshShuffledOrder(list, startIndex = 6, shuffler = reversed)
        assertEquals(listOf("t7", "t10", "t9", "t8", "t6", "t5", "t4", "t3", "t2", "t1"), live.map { it.slotId })

        val store = QueueOrderStore { }
        store.captureFreshShuffled(live, list)
        assertEquals(ids(1..10), store.baselineIds())
    }

    @Test fun e14ASongTapWhileUnshuffledKeepsTheSongsAboveItAsHistoryThatShuffleOnLeavesAlone() {
        val store = storeWith(album(10))

        assertTrue(store.shuffle("t7", shuffler = reversed))

        assertEquals(ids(1..7) + listOf("t10", "t9", "t8"), store.liveIds())
    }

    @Test fun theFreshPlayShuffleKeepsTheStartFirstAndEverySlotOnce() {
        val list = album(30)

        val live = freshShuffledOrder(list, startIndex = 12)

        assertEquals("t13", live.first().slotId)
        assertEquals(list.toSet(), live.toSet())
        assertEquals(list.size, live.size)
    }

    @Test fun aFreshPlayClearsTheExtrasAndTheReorderFlag() {
        val store = storeWith(album(5))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        store.moveUpcoming("t2", toDisplayIndex = 0, currentSlotId = "t1", mirrorToBaseline = false)
        assertTrue(store.extras.isNotEmpty())
        assertTrue(store.shuffleReordered)

        store.captureFreshShuffled(album(3).reversed(), album(3))
        assertTrue(store.extras.isEmpty())
        assertFalse(store.shuffleReordered)

        store.insertAfterCurrent(extra("Y"), "t3", mirrorToBaseline = false)
        store.captureFresh(album(3))
        assertTrue(store.extras.isEmpty())
    }

    // --- session extras ----------------------------------------------------------------------

    @Test fun playNextWhileShuffledIsAPlayNextExtraOutsideBaselineAndStaysLatestFirst() {
        val store = storeWith(album(5))
        store.shuffle("t1", shuffler = reversed)

        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        store.insertAfterCurrent(extra("Y"), "t1", mirrorToBaseline = false)

        assertEquals(listOf("t1", "Y", "X"), store.liveIds().take(3))
        val x = assertNotNull(store.extras["X"])
        val y = assertNotNull(store.extras["Y"])
        assertEquals(QueueExtraKind.PlayNext, x.kind)
        assertEquals(QueueExtraKind.PlayNext, y.kind)
        assertTrue(y.sequence > x.sequence)
        assertFalse("X" in store.baselineIds() || "Y" in store.baselineIds())
    }

    @Test fun addToQueueWhileShuffledIsAnAddToQueueExtraAppendedToTheLiveOrderOnly() {
        val store = storeWith(album(5))
        store.shuffle("t1", shuffler = reversed)

        store.append(extra("Y"), mirrorToBaseline = false)

        assertEquals("Y", store.liveIds().last())
        assertEquals(QueueExtraKind.AddToQueue, store.extras["Y"]?.kind)
        assertFalse("Y" in store.baselineIds())
    }

    @Test fun editsWhileUnshuffledAreOrdinarySlotsInBothOrders() {
        val store = storeWith(album(5))

        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = true)
        store.append(extra("Y"), mirrorToBaseline = true)

        assertTrue(store.extras.isEmpty())
        assertEquals(store.liveIds(), store.baselineIds())
    }

    @Test fun removingAnExtraDropsItsTagAndALibraryDeletionDoesToo() {
        val store = storeWith(album(5))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        store.append(extra("Y"), mirrorToBaseline = false)

        assertTrue(store.remove("X"))
        assertTrue(store.removeAll(listOf("Y")))

        assertTrue(store.extras.isEmpty())
        assertTrue(store.unshuffle("t1"))
        assertEquals(ids(1..5), store.liveIds())
    }

    @Test fun shuffleOffMakesEveryExtraAnOrdinarySlot() {
        val store = storeWith(album(4))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)

        assertTrue(store.unshuffle("t1"))

        assertTrue(store.extras.isEmpty())
        assertTrue("X" in store.baselineIds())
        // A second session shuffles X with the rest, as an ordinary baseline slot.
        assertTrue(store.shuffle("t1", shuffler = reversed))
        assertEquals(listOf("t1", "t4", "t3", "t2", "X"), store.liveIds())
    }

    @Test fun aReorderedExtraIsPlacedByItsKindAndSequenceOnShuffleOff() {
        val store = storeWith(album(4))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        store.insertAfterCurrent(extra("Y"), "t1", mirrorToBaseline = false)
        assertTrue(store.moveUpcoming("X", toDisplayIndex = 0, currentSlotId = "t1", mirrorToBaseline = false))
        assertEquals(listOf("t1", "X", "Y"), store.liveIds().take(3))

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t1", "Y", "X") + ids(2..4), store.liveIds())
    }

    @Test fun anUntaggedLiveSlotMissingFromBaselineIsTreatedAsAddToQueueAfterTheTaggedOnes() {
        val store = QueueOrderStore { }
        store.restore(
            live = listOf(t(1), extra("Z"), t(3), extra("Y"), t(2)),
            baseline = album(3),
            extras = mapOf("Y" to QueueExtraTag(QueueExtraKind.AddToQueue, 4)),
        )

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t1", "t2", "t3", "Y", "Z"), store.liveIds())
    }

    @Test fun baselineSlotsThatAreNotInTheLiveOrderAreNeverResurrected() {
        val store = QueueOrderStore { }
        store.restore(live = listOf(t(1), t(3)), baseline = album(3))

        assertTrue(store.unshuffle("t1", RepeatMode.All))

        assertEquals(listOf("t1", "t3"), store.liveIds())
        assertEquals(listOf("t1", "t3"), store.baselineIds())
    }

    @Test fun twoSlotsOfTheSameSongStayTwoSlotsThroughToggles() {
        val records = listOf(t(1), QueueSlotRecord("t2", "song-same"), t(3), QueueSlotRecord("t4", "song-same"))
        val store = storeWith(records)

        store.shuffle("t1", shuffler = reversed)
        store.unshuffle("t1")

        assertEquals(records, store.live)
    }

    // --- reorder flag ------------------------------------------------------------------------

    @Test fun aReorderWhileShuffledSetsTheFlagOnlyWhenASlotActuallyMoves() {
        val store = storeWith(album(5))
        store.shuffle("t1", shuffler = reversed)
        val ownIndex = store.liveIds().indexOf("t3") - 1

        assertFalse(store.moveUpcoming("t3", ownIndex, "t1", mirrorToBaseline = false))
        assertFalse(store.shuffleReordered)

        assertTrue(store.moveUpcoming("t3", 0, "t1", mirrorToBaseline = false))
        assertTrue(store.shuffleReordered)
    }

    @Test fun e12AReorderMadeWhileShuffledIsDiscardedOnShuffleOffAndClearsTheFlag() {
        val store = storeWith(album(8))
        store.shuffle("t1", shuffler = reversed)
        assertTrue(store.moveUpcoming("t6", 0, "t1", mirrorToBaseline = false))
        assertEquals("t6", store.liveIds()[1])

        assertTrue(store.unshuffle("t1"))

        assertEquals(ids(1..8), store.liveIds())
        assertFalse(store.shuffleReordered)
    }

    @Test fun aReorderWhileUnshuffledNeverSetsTheFlagAndKeepsBothOrdersEqual() {
        val store = storeWith(album(6))

        assertTrue(store.moveUpcoming("t2", 3, "t1", mirrorToBaseline = true))
        assertTrue(store.moveUpcoming("t6", 0, "t1", mirrorToBaseline = true))

        assertFalse(store.shuffleReordered)
        assertEquals(listOf("t1", "t6", "t3", "t4", "t5", "t2"), store.liveIds())
        assertEquals(store.liveIds(), store.baselineIds())
    }

    @Test fun aMirroredMoveFollowsTheSlotNotThePositionWhenTheOrdersDiffer() {
        val writes = mutableListOf<List<String>>()
        val store = QueueOrderStore { records -> writes += records.map { it.slotId } }
        store.restore(live = album(4), baseline = listOf(t(2), t(1), t(3), t(4)))

        assertTrue(store.moveUpcoming("t2", 2, "t1", mirrorToBaseline = true))

        assertEquals(listOf("t1", "t3", "t4", "t2"), store.liveIds())
        assertEquals(listOf("t1", "t3", "t4", "t2"), store.baselineIds())
        assertEquals(listOf(store.baselineIds()), writes)
    }

    @Test fun shuffleOnStartsWithTheReorderFlagCleared() {
        val store = QueueOrderStore { }
        store.restore(live = album(4), baseline = album(4), shuffleReordered = true)

        assertTrue(store.shuffle("t1", shuffler = reversed))

        assertFalse(store.shuffleReordered)
    }

    // --- fail closed -------------------------------------------------------------------------

    @Test fun aShufflerThatDropsASlotIsRejectedAndChangesNothing() {
        val store = storeWith(album(5))
        val live = store.live
        val baseline = store.baseline

        assertFalse(store.shuffle("t2") { it.drop(1) })

        assertEquals(live, store.live)
        assertEquals(baseline, store.baseline)
    }

    @Test fun aShufflerThatDuplicatesASlotIsRejectedAndChangesNothing() {
        val store = storeWith(album(5))
        val live = store.live

        assertFalse(store.shuffle("t2", RepeatMode.All) { it + it.first() })

        assertEquals(live, store.live)
    }

    @Test fun aShufflerThatSwapsInAForeignSlotIsRejected() {
        val store = storeWith(album(3))

        assertFalse(store.shuffle("t1", RepeatMode.All) { pending -> pending.dropLast(1) + t(9) })

        assertEquals(album(3), store.live)
    }

    @Test fun theToggleGuardComparesSlotsAsAMultisetAndRequiresTheCursor() {
        val before = listOf(t(1), t(2), t(3))

        assertTrue(sameSlotsSameCursor(before, listOf(t(3), t(1), t(2)), "t1"))
        assertFalse(sameSlotsSameCursor(before, listOf(t(1), t(2)), "t1"))
        assertFalse(sameSlotsSameCursor(before, listOf(t(1), t(2), t(2)), "t1"))
        assertFalse(sameSlotsSameCursor(before, listOf(t(1), t(2), t(3)), "t4"))
        assertFalse(sameSlotsSameCursor(before, listOf(t(1), t(2), QueueSlotRecord("t3", "other")), "t1"))
    }

    @Test fun aToggleWithAnUnknownCurrentSlotChangesNothing() {
        val store = storeWith(album(3))

        assertFalse(store.shuffle("nope", RepeatMode.All))
        assertFalse(store.unshuffle(null, RepeatMode.All))

        assertEquals(album(3), store.live)
    }
}
