package com.v1per.synthx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The saved play queue written and read back through the real repository and real Room. */
@RunWith(AndroidJUnit4::class)
class RepositoryQueueRoomInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var fx: RepositoryTestFixture
    private val repository get() = fx.repository

    @Before fun setUp() = runBlocking {
        fx = RepositoryTestFixture(ApplicationProvider.getApplicationContext())
        fx.artist("art", "Artist")
        listOf("a", "b", "c").forEach { fx.song(it, title = "Song $it", artistIds = listOf("art")) }
    }

    @After fun tearDown() = fx.close()

    private fun slots(vararg pairs: Pair<String, String>) = pairs.map { (slot, song) -> QueueSlotRecord(slot, song) }

    private fun List<QueueSlot>.pairs() = map { it.slotId to it.song.id }

    @Test fun nothingSavedReadsAsAnEmptyQueueWithNoCursor() = runBlocking {
        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()

        assertTrue(snapshot.slots.isEmpty())
        assertEquals(0, snapshot.storedSlotCount)
        assertNull(snapshot.cursor)
        assertTrue(baseline.isEmpty())
    }

    @Test fun unifiedSaveReadsBackOrderCursorModesAndBaseline() = runBlocking {
        repository.setUnifiedQueueState(
            activeSlots = slots("s3" to "c", "s1" to "a", "s4" to "a", "s2" to "b"),
            baselineSlots = slots("s1" to "a", "s2" to "b", "s3" to "c", "s4" to "a"),
            currentSlotId = "s4",
            positionMs = 12_345,
            shuffleEnabled = true,
            repeatMode = 2,
        )

        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()

        assertEquals(listOf("s3" to "c", "s1" to "a", "s4" to "a", "s2" to "b"), snapshot.slots.pairs())
        assertEquals(4, snapshot.storedSlotCount)
        assertEquals(QueueCursor("s4", 12_345, shuffleEnabled = true, repeatMode = 2), snapshot.cursor)
        assertEquals(listOf("s1" to "a", "s2" to "b", "s3" to "c", "s4" to "a"), baseline.pairs())
        assertEquals("Song a", snapshot.slots[1].song.title)
        assertEquals("Artist", snapshot.slots[1].song.artist)
        // The single-purpose reads agree with the combined one.
        assertEquals(snapshot.slots, repository.getQueueSlotsForTest())
        assertEquals(baseline, repository.getBaselineQueueSlots())
        assertEquals(snapshot, repository.getQueueSnapshot())
    }

    @Test fun aNewSaveReplacesEveryEarlierRow() = runBlocking {
        repository.setUnifiedQueueState(
            slots("s1" to "a", "s2" to "b", "s3" to "c"),
            slots("s1" to "a", "s2" to "b", "s3" to "c"),
            "s1",
            0,
            false,
            0,
        )

        repository.setUnifiedQueueState(slots("t1" to "b"), slots("t1" to "b"), "t1", 5, false, 1)

        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()
        assertEquals(listOf("t1" to "b"), snapshot.slots.pairs())
        assertEquals(1, snapshot.storedSlotCount)
        assertEquals(listOf("t1" to "b"), baseline.pairs())
        assertEquals(QueueCursor("t1", 5, shuffleEnabled = false, repeatMode = 1), snapshot.cursor)
    }

    @Test fun pinnedMissingSongsAreLeftOutOnReadButStillCounted() = runBlocking {
        fx.song("gone", missing = true)
        repository.setUnifiedQueueState(
            slots("s1" to "a", "s2" to "gone", "s3" to "b"),
            slots("s2" to "gone", "s1" to "a", "s3" to "b"),
            "s2",
            99,
            false,
            0,
        )

        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()

        assertEquals(listOf("s1" to "a", "s3" to "b"), snapshot.slots.pairs())
        assertEquals("rows are still stored", 3, snapshot.storedSlotCount)
        assertEquals("the cursor still names the hidden slot", "s2", snapshot.cursor?.currentSlotId)
        assertEquals(listOf("s1" to "a", "s3" to "b"), baseline.pairs())
        assertEquals(3, fx.queueDao.getBaselineQueueItems().size)
    }

    @Test fun pinnedDeletingASongRowRemovesItsQueueRowsFromBothOrders() = runBlocking {
        repository.setUnifiedQueueState(
            slots("s1" to "a", "s2" to "b", "s3" to "a"),
            slots("s1" to "a", "s3" to "a", "s2" to "b"),
            "s2",
            0,
            false,
            0,
        )

        fx.database.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE id = 'a'")

        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()
        assertEquals(listOf("s2" to "b"), snapshot.slots.pairs())
        assertEquals(1, snapshot.storedSlotCount)
        assertEquals(listOf("s2" to "b"), baseline.pairs())
    }

    @Test fun queueSaveKeepsTheSavedShuffleAndRepeatModes() = runBlocking {
        repository.setUnifiedQueueState(slots("s1" to "a"), slots("s1" to "a"), "s1", 0, true, 1)

        repository.setQueueState(slots("n1" to "c", "n2" to "a"), currentSlotId = "n2", positionMs = 700)

        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()
        assertEquals(listOf("n1" to "c", "n2" to "a"), snapshot.slots.pairs())
        assertEquals(QueueCursor("n2", 700, shuffleEnabled = true, repeatMode = 1), snapshot.cursor)
        assertEquals("the baseline is a separate write", listOf("s1" to "a"), baseline.pairs())
    }

    @Test fun cursorOnlySaveLeavesTheQueueAndModesAlone() = runBlocking {
        repository.setUnifiedQueueState(
            slots("s1" to "a", "s2" to "b"),
            slots("s2" to "b", "s1" to "a"),
            "s1",
            10,
            true,
            2,
        )

        repository.setCursorAlreadyAdmitted("s2", 4_000)

        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()
        assertEquals(listOf("s1" to "a", "s2" to "b"), snapshot.slots.pairs())
        assertEquals(QueueCursor("s2", 4_000, shuffleEnabled = true, repeatMode = 2), snapshot.cursor)
        assertEquals(listOf("s2" to "b", "s1" to "a"), baseline.pairs())
    }

    @Test fun shuffleAndRepeatSavesChangeOnlyTheirOwnValue() = runBlocking {
        repository.setUnifiedQueueState(
            slots("s1" to "a", "s2" to "b"),
            slots("s1" to "a", "s2" to "b"),
            "s2",
            321,
            false,
            0,
        )

        repository.setShuffleEnabledAlreadyAdmitted(true)
        assertEquals(
            QueueCursor("s2", 321, shuffleEnabled = true, repeatMode = 0),
            repository.getQueueSnapshot().cursor,
        )

        repository.setRepeatModeAlreadyAdmitted(1)
        assertEquals(
            QueueCursor("s2", 321, shuffleEnabled = true, repeatMode = 1),
            repository.getQueueSnapshot().cursor,
        )
        assertEquals(listOf("s1" to "a", "s2" to "b"), repository.getQueueSlotsForTest().pairs())
    }

    @Test fun modeSavesBeforeAnyQueueCreateTheCursorRow() = runBlocking {
        repository.setShuffleEnabledAlreadyAdmitted(true)
        assertEquals(QueueCursor(null, 0, shuffleEnabled = true, repeatMode = 0), repository.getQueueSnapshot().cursor)

        repository.setRepeatModeAlreadyAdmitted(2)
        assertEquals(QueueCursor(null, 0, shuffleEnabled = true, repeatMode = 2), repository.getQueueSnapshot().cursor)
    }

    @Test fun baselineSaveReplacesOnlyTheBaseline() = runBlocking {
        repository.setUnifiedQueueState(
            slots("s1" to "a", "s2" to "b"),
            slots("s1" to "a", "s2" to "b"),
            "s1",
            0,
            true,
            0,
        )

        repository.setBaselineQueueSlots(slots("s2" to "b", "x" to "c", "s1" to "a"))

        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()
        assertEquals(listOf("s2" to "b", "x" to "c", "s1" to "a"), baseline.pairs())
        assertEquals(listOf("s1" to "a", "s2" to "b"), snapshot.slots.pairs())
        assertEquals("s1", snapshot.cursor?.currentSlotId)
    }

    @Test fun pinnedDebugCheckFiresAfterACursorOutsideTheQueueIsSaved() = runBlocking {
        try {
            repository.setQueueState(slots("s1" to "a"), currentSlotId = "elsewhere", positionMs = 0)
            fail("the debug build checks that the cursor names a saved slot")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("elsewhere"))
        }

        val snapshot = repository.getQueueSnapshot()
        assertEquals("the write landed before the check", listOf("s1" to "a"), snapshot.slots.pairs())
        assertEquals("elsewhere", snapshot.cursor?.currentSlotId)
    }
}
