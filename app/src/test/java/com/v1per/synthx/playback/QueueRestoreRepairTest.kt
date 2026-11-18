package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The repair rules a restored queue goes through before the engine adopts it, and the read path
 * that carries the extras and the reorder flag out of storage. A repair never changes the live
 * order: it only rebuilds what shuffle OFF needs so that no slot is lost and none comes back.
 */
class QueueRestoreRepairTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun t(index: Int) = QueueSlotRecord("t$index", "song-t$index")

    private fun extra(id: String) = QueueSlotRecord(id, "song-$id")

    private fun playNext(sequence: Long) = QueueExtraTag(QueueExtraKind.PlayNext, sequence)

    private fun addToQueue(sequence: Long) = QueueExtraTag(QueueExtraKind.AddToQueue, sequence)

    private fun List<QueueSlotRecord>.ids() = map { it.slotId }

    @Test fun unshuffledBaselineIsTheLiveOrderWithNoExtrasAndNoFlag() {
        val repaired = repairRestoredQueue(
            live = listOf(t(1), t(2), t(3)),
            baseline = listOf(t(3), t(1)),
            extras = mapOf("t2" to playNext(1)),
            currentSlotId = "t1",
            shuffleEnabled = false,
            shuffleReordered = true,
        )

        assertEquals(listOf("t1", "t2", "t3"), repaired.baseline.ids())
        assertTrue(repaired.extras.isEmpty())
        assertFalse(repaired.shuffleReordered)
    }

    @Test fun shuffledWithAnEmptyBaselineRebuildsItFromTheLiveOrderWithoutTheTaggedExtras() {
        val repaired = repairRestoredQueue(
            live = listOf(t(2), extra("X"), t(3), t(1)),
            baseline = emptyList(),
            extras = mapOf("X" to playNext(0)),
            currentSlotId = "t2",
            shuffleEnabled = true,
            shuffleReordered = false,
        )

        assertEquals(listOf("t2", "t3", "t1"), repaired.baseline.ids())
        assertEquals(mapOf("X" to playNext(0)), repaired.extras)
    }

    @Test fun shuffledWithAnEmptyBaselineAndAnExtraPlayingStillRebuildsItAndShuffleOffKeepsTheLiveOrder() {
        val live = listOf(t(2), extra("X"), t(3), extra("Y"), t(1))
        val extras = mapOf("X" to playNext(0), "Y" to addToQueue(1))
        val repaired =
            repairRestoredQueue(live, emptyList(), extras, "X", shuffleEnabled = true, shuffleReordered = false)
        assertEquals(listOf("t2", "t3", "t1"), repaired.baseline.ids())

        val store = QueueOrderStore { }
        store.restore(live, repaired.baseline, repaired.extras, repaired.shuffleReordered)
        assertTrue(store.unshuffle("X"))

        assertEquals(listOf("t2", "X", "t3", "t1", "Y"), store.live.ids())
    }

    @Test fun shuffledWithABaselineMissingAnUntaggedCurrentSlotRebuildsIt() {
        val repaired = repairRestoredQueue(
            live = listOf(t(2), t(3), t(1)),
            baseline = listOf(t(1), t(3)),
            extras = emptyMap(),
            currentSlotId = "t2",
            shuffleEnabled = true,
            shuffleReordered = false,
        )

        assertEquals(listOf("t2", "t3", "t1"), repaired.baseline.ids())
        assertTrue(repaired.extras.isEmpty())
    }

    @Test fun shuffledWithATaggedExtraAsTheCurrentSlotKeepsTheBaseline() {
        val repaired = repairRestoredQueue(
            live = listOf(t(1), extra("X"), t(3), t(2)),
            baseline = listOf(t(1), t(2), t(3)),
            extras = mapOf("X" to playNext(0)),
            currentSlotId = "X",
            shuffleEnabled = true,
            shuffleReordered = false,
        )

        assertEquals(listOf("t1", "t2", "t3"), repaired.baseline.ids())
        assertEquals(mapOf("X" to playNext(0)), repaired.extras)
    }

    @Test fun shuffledWithABaselineSharingNoSlotWithTheLiveOrderRebuildsIt() {
        val repaired = repairRestoredQueue(
            live = listOf(t(2), t(1)),
            baseline = listOf(t(8), t(9)),
            extras = emptyMap(),
            currentSlotId = "t2",
            shuffleEnabled = true,
            shuffleReordered = false,
        )

        assertEquals(listOf("t2", "t1"), repaired.baseline.ids())
    }

    @Test fun untaggedLiveSlotsMissingFromBaselineBecomeAddToQueueExtrasInLiveOrderAfterTheTaggedOnes() {
        val repaired = repairRestoredQueue(
            live = listOf(t(1), extra("B"), t(3), extra("A"), extra("C"), t(2)),
            baseline = listOf(t(1), t(2), t(3)),
            extras = mapOf("C" to addToQueue(7)),
            currentSlotId = "t1",
            shuffleEnabled = true,
            shuffleReordered = true,
        )

        assertEquals(addToQueue(7), repaired.extras["C"])
        val b = assertNotNull(repaired.extras["B"])
        val a = assertNotNull(repaired.extras["A"])
        assertEquals(QueueExtraKind.AddToQueue, b.kind)
        assertEquals(QueueExtraKind.AddToQueue, a.kind)
        assertTrue(b.sequence > 7 && a.sequence > b.sequence, "after the tagged ones, in live order")
        assertTrue(repaired.shuffleReordered)
    }

    @Test fun baselineSlotsNotInTheLiveOrderAndTagsOfSlotsNotInItAreDropped() {
        val repaired = repairRestoredQueue(
            live = listOf(t(1), t(3)),
            baseline = listOf(t(1), t(2), t(3)),
            extras = mapOf("gone" to playNext(2)),
            currentSlotId = "t1",
            shuffleEnabled = true,
            shuffleReordered = false,
        )

        assertEquals(listOf("t1", "t3"), repaired.baseline.ids())
        assertTrue(repaired.extras.isEmpty())
    }

    @Test fun aConsistentShuffledStateIsLeftExactlyAsItWas() {
        val live = listOf(t(1), extra("X"), t(4), t(2), t(3), extra("Y"))
        val baseline = listOf(t(1), t(2), t(3), t(4))
        val extras = mapOf("X" to playNext(3), "Y" to addToQueue(4))

        val repaired = repairRestoredQueue(live, baseline, extras, "X", shuffleEnabled = true, shuffleReordered = true)

        assertEquals(baseline, repaired.baseline)
        assertEquals(extras, repaired.extras)
        assertTrue(repaired.shuffleReordered)
    }

    @Test fun e16AfterRepairShuffleOffKeepsEveryRestoredSlotInTheCurrentOrder() {
        val live = listOf(t(3), t(1), t(4), t(2))
        val repaired =
            repairRestoredQueue(live, emptyList(), emptyMap(), "t1", shuffleEnabled = true, shuffleReordered = false)
        val store = QueueOrderStore { }
        store.restore(live, repaired.baseline, repaired.extras, repaired.shuffleReordered)

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t3", "t1", "t4", "t2"), store.live.ids())
    }

    @Test fun aShuffledQueueSavedBeforeExtrasWereTaggedTreatsThemAsAddToQueue() {
        val live = listOf(t(1), extra("X"), t(3), t(2))
        val baseline = listOf(t(1), t(2), t(3))
        val repaired =
            repairRestoredQueue(live, baseline, emptyMap(), "t1", shuffleEnabled = true, shuffleReordered = false)
        val store = QueueOrderStore { }
        store.restore(live, repaired.baseline, repaired.extras, repaired.shuffleReordered)

        assertTrue(store.unshuffle("t1"))

        assertEquals(listOf("t1", "t2", "t3", "X"), store.live.ids())
    }

    @Test fun aRestoredStoreContinuesTheSequenceAfterTheHighestRestoredTag() {
        val store = QueueOrderStore { }
        store.restore(
            live = listOf(t(1), extra("X"), t(2)),
            baseline = listOf(t(1), t(2)),
            extras = mapOf("X" to playNext(41)),
        )

        store.insertAfterCurrent(extra("Y"), "t1", mirrorToBaseline = false)

        val y = assertNotNull(store.extras["Y"])
        assertTrue(y.sequence > 41)
        assertTrue(store.unshuffle("t1"))
        assertEquals(listOf("t1", "Y", "X", "t2"), store.live.ids())
    }

    // --- the read path ---------------------------------------------------------------------

    private class SnapshotRepo(var snapshot: QueueSnapshot, var baseline: List<QueueSlot>) :
        SongRepository by unexpectedCalls() {
        override suspend fun getQueueSnapshot(): QueueSnapshot = snapshot
        override suspend fun getBaselineQueueSlots(): List<QueueSlot> = baseline
        override suspend fun getQueueSnapshotWithBaseline(): Pair<QueueSnapshot, List<QueueSlot>> = snapshot to baseline
    }

    private fun slots(vararg ids: String) = ids.map { QueueSlot(it, Song("song-$it", it, "", "", 0L, "")) }

    @Test fun bothReadsCarryTheExtrasAndTheReorderFlag() = runTest {
        val extras = mapOf("X" to playNext(5))
        val repo = SnapshotRepo(
            QueueSnapshot(
                slots("t1", "X", "t2"),
                3,
                QueueCursor("t1", 0L, shuffleEnabled = true, repeatMode = 0, shuffleReordered = true),
                extras,
            ),
            slots("t1", "t2"),
        )
        val persistence = QueuePersistence(repo)
        try {
            val restored = assertNotNull(persistence.loadQueue())
            assertEquals(extras, restored.extras)
            assertTrue(restored.shuffleReordered)

            val (adopted, _) = persistence.loadQueueWithBaseline()
            assertEquals(extras, assertNotNull(adopted).extras)
            assertTrue(adopted.shuffleReordered)
        } finally {
            persistence.quiesceForRestore()
        }
    }

    @Test fun aCursorWithoutTheFlagReadsAsNotReordered() = runTest {
        val repo = SnapshotRepo(QueueSnapshot(slots("t1"), 1, QueueCursor("t1", 0L, true, 0)), emptyList())
        val persistence = QueuePersistence(repo)
        try {
            val restored = assertNotNull(persistence.loadQueue())
            assertFalse(restored.shuffleReordered)
            assertTrue(restored.extras.isEmpty())
        } finally {
            persistence.quiesceForRestore()
        }
    }

    private companion object {
        fun unexpectedCalls(): SongRepository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ -> error("Unexpected repository call: ${method.name}") } as SongRepository
    }
}
