package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The read half of [QueuePersistence] that cold-start restore and session adoption start from:
 * how many saved rows no longer resolve, when there is nothing to restore, the baseline read, the
 * combined read, and what a failing read turns into. The device side is QueueColdStartInstrumentedTest.
 */
class QueuePersistenceReadTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    /** Serves the three queue reads; any other repository call fails the test. */
    private class ReadRepo : SongRepository by unexpectedCalls() {
        var snapshot = QueueSnapshot(emptyList(), 0, null)
        var baseline: List<QueueSlot> = emptyList()
        var failReads = false

        override suspend fun getQueueSnapshot(): QueueSnapshot {
            if (failReads) error("database failure")
            return snapshot
        }

        override suspend fun getBaselineQueueSlots(): List<QueueSlot> {
            if (failReads) error("database failure")
            return baseline
        }

        override suspend fun getQueueSnapshotWithBaseline(): Pair<QueueSnapshot, List<QueueSlot>> {
            if (failReads) error("database failure")
            return snapshot to baseline
        }
    }

    private val repo = ReadRepo()
    private val persistence = QueuePersistence(repo)

    /** Leaves no live instance behind for the instance-count checks in QueuePersistenceB1Test. */
    @After fun closeInstance() = runTest { persistence.quiesceForRestore() }

    private fun slots(vararg ids: String) = ids.map { QueueSlot("slot-$it", Song(it, it, "", "", 0L, "")) }

    @Test fun droppedCountIsTheSavedRowsThatNoLongerResolve() = runTest {
        repo.snapshot = QueueSnapshot(slots("a", "c", "e"), 5, QueueCursor("slot-c", 9_000L, true, 1))

        val restored = assertNotNull(persistence.loadQueue())

        assertEquals(2, restored.droppedCount)
        assertEquals(listOf("slot-a", "slot-c", "slot-e"), restored.slots.map { it.slotId })
        assertEquals(1, restored.startIndex)
        assertEquals(9_000L, restored.positionMs)
    }

    @Test fun aSavedQueueWhoseSongsAreAllGoneRestoresNothing() = runTest {
        repo.snapshot = QueueSnapshot(emptyList(), 4, QueueCursor("slot-x", 1_000L, false, 0))
        assertNull(persistence.loadQueue())
    }

    @Test fun nothingSavedRestoresNothing() = runTest {
        assertNull(persistence.loadQueue())
    }

    @Test fun noCursorStartsAtTheFirstSlotWithDefaultModes() = runTest {
        repo.snapshot = QueueSnapshot(slots("a", "b"), 2, null)

        val restored = assertNotNull(persistence.loadQueue())

        assertEquals(0, restored.startIndex)
        assertEquals(0L, restored.positionMs)
        assertFalse(restored.shuffleEnabled)
        assertEquals(0, restored.repeatMode)
        assertEquals(0, restored.droppedCount)
    }

    @Test fun theBaselineIsReadAsSaved() = runTest {
        repo.baseline = slots("b", "a", "c")
        assertEquals(listOf("slot-b", "slot-a", "slot-c"), persistence.loadBaselineQueue().map { it.slotId })
    }

    @Test fun theCombinedReadReturnsTheQueueAndTheBaselineTogether() = runTest {
        repo.snapshot = QueueSnapshot(slots("a", "b"), 3, QueueCursor("slot-b", 500L, true, 2))
        repo.baseline = slots("b", "a")

        val (restored, baseline) = persistence.loadQueueWithBaseline()

        assertEquals(1, assertNotNull(restored).droppedCount)
        assertEquals(1, restored.startIndex)
        assertEquals(listOf("slot-b", "slot-a"), baseline.map { it.slotId })
    }

    @Test fun failingReadsRestoreNothingInsteadOfThrowing() = runTest {
        repo.snapshot = QueueSnapshot(slots("a"), 1, null)
        repo.baseline = slots("a")
        repo.failReads = true

        assertNull(persistence.loadQueue())
        assertEquals(emptyList(), persistence.loadBaselineQueue())
        assertEquals(null to emptyList(), persistence.loadQueueWithBaseline())
    }

    private companion object {
        fun unexpectedCalls(): SongRepository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ -> error("Unexpected repository call: ${method.name}") } as SongRepository
    }
}
