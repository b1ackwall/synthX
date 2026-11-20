package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cold-start read: where a queue whose saved song is gone starts, the saved modes of a cursor
 * whose queue has no surviving slot, and the stored customised-queue flag on both reads.
 */
class QueuePersistenceRestoreReadTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private class ReadRepo : SongRepository by unexpectedCalls() {
        var snapshot = QueueSnapshot(emptyList(), 0, null)
        var failReads = false

        override suspend fun getQueueSnapshot(): QueueSnapshot {
            if (failReads) error("database failure")
            return snapshot
        }

        override suspend fun getQueueSnapshotWithBaseline(): Pair<QueueSnapshot, List<QueueSlot>> {
            if (failReads) error("database failure")
            return snapshot to snapshot.slots
        }
    }

    private val repo = ReadRepo()
    private val persistence = QueuePersistence(repo)

    @After fun closeInstance() = runTest { persistence.quiesceForRestore() }

    private fun slots(vararg ids: String) = ids.map { QueueSlot("slot-$it", Song(it, it, "", "", 0L, "")) }

    /** Five saved rows a..e at positions 0..4; [gone] no longer resolves. */
    private fun serveFiveWithGone(gone: String, current: String, position: Int?, shuffle: Boolean = false) {
        val all = listOf("a", "b", "c", "d", "e")
        repo.snapshot = QueueSnapshot(
            slots = slots(*all.filterNot { it == gone }.toTypedArray()),
            storedSlotCount = 5,
            cursor = QueueCursor("slot-$current", 9_000L, shuffle, 2),
            storedPositions = all.mapIndexed { index, id -> "slot-$id" to index }.toMap(),
            currentQueuePosition = position,
        )
    }

    @Test fun aGoneCurrentSongStartsAtTheNextSurvivorAtZero() = runTest {
        serveFiveWithGone(gone = "c", current = "c", position = 2)

        val restored = assertNotNull(persistence.loadQueue())

        assertEquals("slot-d", restored.slots[restored.startIndex].slotId)
        assertEquals(0L, restored.positionMs)
        assertEquals(1, restored.droppedCount)
    }

    @Test fun aGoneLastSongStartsAtTheLastSurvivorBeforeIt() = runTest {
        serveFiveWithGone(gone = "e", current = "e", position = 4)

        val restored = assertNotNull(persistence.loadQueue())

        assertEquals("slot-d", restored.slots[restored.startIndex].slotId)
        assertEquals(0L, restored.positionMs)
    }

    @Test fun aLegacyCursorWithoutAPositionStartsAtTheFirstSurvivor() = runTest {
        serveFiveWithGone(gone = "c", current = "c", position = null)

        val restored = assertNotNull(persistence.loadQueue())

        assertEquals(0, restored.startIndex)
        assertEquals(0L, restored.positionMs)
    }

    @Test fun aSurvivingCurrentSongKeepsItsSlotAndPosition() = runTest {
        serveFiveWithGone(gone = "a", current = "c", position = 2)

        val restored = assertNotNull(persistence.loadQueue())

        assertEquals("slot-c", restored.slots[restored.startIndex].slotId)
        assertEquals(9_000L, restored.positionMs)
    }

    @Test fun theStoredCustomisedFlagIsReadOnBothReads() = runTest {
        repo.snapshot = QueueSnapshot(slots("a", "b"), 2, QueueCursor("slot-a", 0L, false, 0, queueCustomized = true))

        assertTrue(assertNotNull(persistence.loadQueue()).queueCustomized)
        assertTrue(assertNotNull(persistence.loadQueueWithBaseline().first).queueCustomized)
    }

    @Test fun aLegacyCursorReadsAsUntouched() = runTest {
        repo.snapshot = QueueSnapshot(slots("a"), 1, QueueCursor("slot-a", 0L, false, 0))

        assertFalse(assertNotNull(persistence.loadQueue()).queueCustomized)
    }

    @Test fun aCursorWithEverySavedSongGoneStillCarriesItsModes() = runTest {
        repo.snapshot = QueueSnapshot(emptyList(), 3, QueueCursor("slot-x", 1_000L, shuffleEnabled = true, 2))

        val read = assertIs<QueuePersistence.RestoreRead.ModesOnly>(persistence.loadRestore())

        assertEquals(QueuePersistence.RestoreRead.ModesOnly(shuffleEnabled = true, repeatMode = 2), read)
        assertNull(persistence.loadQueue(), "no slot survives, so there is still no queue to restore")
    }

    @Test fun aCursorWithNoQueueRowsCarriesItsModes() = runTest {
        repo.snapshot = QueueSnapshot(emptyList(), 0, QueueCursor(null, 0L, shuffleEnabled = false, 1))

        assertEquals(
            QueuePersistence.RestoreRead.ModesOnly(shuffleEnabled = false, repeatMode = 1),
            persistence.loadRestore(),
        )
    }

    @Test fun nothingSavedAndAFailedReadBothReadAsEmpty() = runTest {
        assertEquals(QueuePersistence.RestoreRead.Empty, persistence.loadRestore())
        repo.snapshot = QueueSnapshot(slots("a"), 1, QueueCursor("slot-a", 0L, true, 2))
        repo.failReads = true
        assertEquals(QueuePersistence.RestoreRead.Empty, persistence.loadRestore())
    }

    @Test fun aQueueReadIsTheSameQueueLoadQueueReturns() = runTest {
        serveFiveWithGone(gone = "b", current = "d", position = 3)

        val read = assertIs<QueuePersistence.RestoreRead.Queue>(persistence.loadRestore())

        assertEquals(persistence.loadQueue(), read.queue)
    }

    private companion object {
        fun unexpectedCalls(): SongRepository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ -> error("unexpected repository call: ${method.name}") } as SongRepository
    }
}
