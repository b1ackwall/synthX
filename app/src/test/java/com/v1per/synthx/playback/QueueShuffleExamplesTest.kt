package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The worked examples of the shuffle design (docs/queue-shuffle-design.md), with Repeat All off,
 * run against [QueueOrderStore] with a fixed shuffler so every expected list is exact. Slot ids
 * t1..tN are the album songs; X and Y are songs added with Play Next or Add to Queue. The cursor
 * is the slot id passed to each call: natural progression only moves it.
 */
class QueueShuffleExamplesTest {
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

    /** A shuffler that returns the pending slots in exactly [order]. */
    private fun fixedOrder(vararg order: String): (List<QueueSlotRecord>) -> List<QueueSlotRecord> = { pending ->
        order.map { id -> pending.single { it.slotId == id } }
    }

    @Test fun e1OnThenOffRightAwayRestoresTheAlbumWithTheHistoryUntouched() {
        val store = storeWith(album(14))

        assertTrue(store.shuffle("t4", shuffler = reversed))
        assertEquals(ids(1..4) + ids(14 downTo 5), store.liveIds(), "only the songs after t4 are shuffled")
        assertEquals(ids(1..14), store.baselineIds())

        assertTrue(store.unshuffle("t4"))
        assertEquals(ids(1..14), store.liveIds())
        assertEquals(ids(1..14), store.baselineIds())
    }

    @Test fun e2ShuffleButtonThenOffContinuesAfterTheStartSongAndWrapsToTheSongsBeforeIt() {
        val store = QueueOrderStore { }
        val list = album(12)
        store.captureFreshShuffled(listOf(t(7)) + (list - t(7)).reversed(), list)

        assertTrue(store.unshuffle("t7"))

        val expected = ids(7..12) + ids(1..6)
        assertEquals(expected, store.liveIds())
        assertEquals(expected, store.baselineIds())
    }

    @Test fun e3OffWhileASongQueuedDuringShuffleIsPlayingKeepsTheWholeAlbum() {
        val store = storeWith(album(8))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)

        // Playback advances to X: t1 is now history.
        assertTrue(store.unshuffle("X"))

        val expected = listOf("t1", "X") + ids(2..8)
        assertEquals(expected, store.liveIds())
        assertEquals(expected, store.baselineIds())
    }

    @Test fun e4OffBeforeAPlayNextSongPlaysKeepsItNext() {
        val store = storeWith(album(8))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t1", "X") + ids(2..8), store.liveIds())
    }

    @Test fun e5AHeardPlayNextSongStaysInHistoryAndTheAlbumContinuesAfterTheCurrentSong() {
        val store = storeWith(album(8))
        store.shuffle("t1", shuffler = fixedOrder("t5", "t3", "t8", "t2", "t7", "t4", "t6"))
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        assertEquals(listOf("t1", "X", "t5"), store.liveIds().take(3))

        // Playback advances to X, then to t5.
        assertTrue(store.unshuffle("t5"))

        assertEquals(listOf("t1", "X", "t5", "t6", "t7", "t8", "t2", "t3", "t4"), store.liveIds())
        assertEquals(1, store.liveIds().count { it == "X" }, "a heard Play Next song is never queued again")
    }

    @Test fun e7APlayNextSongAddedWhileUnshuffledIsShuffledAndThenRestoredToItsPlace() {
        val store = storeWith(album(6))
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = true)
        assertEquals(listOf("t1", "X") + ids(2..6), store.liveIds())
        assertEquals(store.liveIds(), store.baselineIds())

        store.shuffle("t1", shuffler = reversed)
        assertEquals(listOf("t1") + ids(6 downTo 2) + "X", store.liveIds(), "X is shuffled with the rest")

        assertTrue(store.unshuffle("t1"))
        assertEquals(listOf("t1", "X") + ids(2..6), store.liveIds())
    }

    @Test fun e8AddToQueueWhileShuffledStaysLastAfterShuffleOff() {
        val store = storeWith(album(6))
        store.shuffle("t1", shuffler = reversed)
        store.append(extra("Y"), mirrorToBaseline = false)
        assertEquals("Y", store.liveIds().last())

        assertTrue(store.unshuffle("t1"))

        assertEquals(ids(1..6) + "Y", store.liveIds())
    }

    @Test fun e9RepeatedPlayNextWhileShuffledStaysLatestFirstThroughShuffleOff() {
        val store = storeWith(album(6))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        store.insertAfterCurrent(extra("Y"), "t1", mirrorToBaseline = false)
        assertEquals(listOf("t1", "Y", "X"), store.liveIds().take(3))

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t1", "Y", "X") + ids(2..6), store.liveIds())
    }

    @Test fun e10ARemovalWhileShuffledStaysRemovedAfterShuffleOff() {
        val store = storeWith(album(6))
        store.shuffle("t1", shuffler = reversed)
        assertTrue(store.remove("t3"))

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t1", "t2", "t4", "t5", "t6"), store.liveIds())
        assertFalse("t3" in store.baselineIds())
    }

    @Test fun e10ARemovalWhileUnshuffledStaysRemovedThroughOnAndOff() {
        val store = storeWith(album(6))
        assertTrue(store.remove("t3"))

        store.shuffle("t1", shuffler = reversed)
        assertFalse("t3" in store.liveIds() || "t3" in store.baselineIds())
        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t1", "t2", "t4", "t5", "t6"), store.liveIds())
        assertFalse("t3" in store.baselineIds())
    }

    @Test fun e11AReorderMadeWhileUnshuffledSurvivesOnAndOff() {
        val store = storeWith(album(6))
        assertTrue(store.moveUpcoming("t5", toDisplayIndex = 0, currentSlotId = "t1", mirrorToBaseline = true))
        val reordered = listOf("t1", "t5", "t2", "t3", "t4", "t6")
        assertEquals(reordered, store.liveIds())
        assertEquals(reordered, store.baselineIds())

        store.shuffle("t1", shuffler = reversed)
        assertTrue(store.unshuffle("t1"))

        assertEquals(reordered, store.liveIds())
    }

    @Test fun e13ShuffleOnWithNothingPendingKeepsTheOrderAndAPlayNextSongStaysNext() {
        val store = storeWith(album(5))

        assertTrue(store.shuffle("t5", shuffler = reversed))
        assertEquals(ids(1..5), store.liveIds())
        assertEquals(ids(1..5), store.baselineIds())

        store.insertAfterCurrent(extra("X"), "t5", mirrorToBaseline = false)
        assertEquals(listOf("X"), store.upcomingFrom("t5").map { it.slotId })

        assertTrue(store.unshuffle("t5"))
        assertEquals(ids(1..5) + "X", store.liveIds())
    }

    @Test fun e15AnExtraCurrentSongWithNoAlbumSongBeforeItContinuesWithTheAlbumInOrder() {
        val store = storeWith(album(8))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        // Playback advances to X, then t1's song is deleted from the library.
        assertTrue(store.removeAll(listOf("t1")))
        assertEquals("X", store.liveIds().first())

        assertTrue(store.unshuffle("X"))

        assertEquals(listOf("X") + ids(2..8), store.liveIds())
    }

    @Test fun e16AnEmptyBaselineWhileShuffledLosesNothingAndKeepsTheCurrentOrder() {
        val store = QueueOrderStore { }
        store.restore(live = listOf(t(3), t(1), t(4), t(2)), baseline = emptyList())

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t3", "t1", "t4", "t2"), store.liveIds())
        assertEquals(store.liveIds(), store.baselineIds())
    }

    @Test fun e16APartialBaselineWhileShuffledLosesNothing() {
        val store = QueueOrderStore { }
        store.restore(live = listOf(t(1), t(5), t(3), t(2), t(4)), baseline = listOf(t(1), t(2), t(3)))

        assertTrue(store.unshuffle("t1"))

        // t2 and t3 follow their baseline order; t5 and t4, missing from it, keep their live order last.
        assertEquals(listOf("t1", "t2", "t3", "t5", "t4"), store.liveIds())
    }

    @Test fun e16AnEmptyBaselineWhileUnshuffledIsRebuiltFromTheLiveOrderOnShuffleOn() {
        val store = QueueOrderStore { }
        store.restore(live = album(4), baseline = emptyList())

        assertTrue(store.shuffle("t2", shuffler = reversed))

        assertEquals(listOf("t1", "t2", "t4", "t3"), store.liveIds())
        assertEquals(ids(1..4), store.baselineIds())
    }

    @Test fun s1OffWhileTheSongQueuedDuringShuffleIsPlayingKeepsTheAlbum() {
        val store = storeWith(album(8))
        store.shuffle("t1", shuffler = fixedOrder("t5", "t2", "t7", "t3", "t4", "t8", "t6"))
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)

        assertTrue(store.unshuffle("X"))

        assertEquals(listOf("t1", "X") + ids(2..8), store.liveIds())
    }

    @Test fun s2OffBeforeThePlayNextSongPlaysKeepsItNextRatherThanLast() {
        val store = storeWith(album(8))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t1", "X") + ids(2..8), store.liveIds())
    }

    @Test fun s3AHeardPlayNextSongIsNotQueuedAgainAndNoAlbumSongIsLost() {
        val store = storeWith(album(8))
        store.shuffle("t1", shuffler = reversed)
        store.insertAfterCurrent(extra("X"), "t1", mirrorToBaseline = false)
        assertEquals(listOf("t1", "X", "t8"), store.liveIds().take(3))

        // X plays, then playback moves on to t8.
        assertTrue(store.unshuffle("t8"))

        assertEquals(listOf("t1", "X", "t8") + ids(2..7), store.liveIds())
    }

    @Test fun s4ShuffleButtonThenOffWithNothingPlayedKeepsTheSongsBeforeTheStart() {
        val store = QueueOrderStore { }
        val list = album(12)
        store.captureFreshShuffled(listOf(t(7)) + (list - t(7)).reversed(), list)

        assertTrue(store.unshuffle("t7"))

        assertEquals(12, store.live.size)
        assertEquals(ids(7..12) + ids(1..6), store.liveIds())
    }

    @Test fun s5OnTwoSongsOffAndOnAgainNeverShrinksTheQueue() {
        val store = storeWith(album(12))
        store.shuffle("t6", shuffler = reversed)
        assertEquals(ids(1..6) + ids(12 downTo 7), store.liveIds())

        // Two songs play: t12, then t11.
        assertTrue(store.unshuffle("t11"))
        assertEquals(ids(1..6) + listOf("t12", "t11") + ids(7..10), store.liveIds())

        assertTrue(store.shuffle("t11", shuffler = reversed))
        assertEquals(ids(1..6) + listOf("t12", "t11") + ids(10 downTo 7), store.liveIds())
        assertEquals(12, store.live.size)
    }
}
