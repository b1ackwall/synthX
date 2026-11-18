package com.v1per.synthx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session-extra tags and the shuffle reorder flag through the real repository and real
 * in-memory Room. Every write that rebuilds the cursor row is covered: none of them may clear the
 * flag unless it is the write that sets or resets it on purpose.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryQueueExtrasRoomInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var fx: RepositoryTestFixture
    private val repository get() = fx.repository

    private val playNext = QueueExtraTag(QueueExtraKind.PlayNext, 3)
    private val addToQueue = QueueExtraTag(QueueExtraKind.AddToQueue, 4)

    @Before fun setUp() = runBlocking {
        fx = RepositoryTestFixture(ApplicationProvider.getApplicationContext())
        listOf("a", "b", "c", "d").forEach { fx.song(it, title = "Song $it") }
    }

    @After fun tearDown() = fx.close()

    private fun slots(vararg pairs: Pair<String, String>) = pairs.map { (slot, song) -> QueueSlotRecord(slot, song) }

    /** A shuffled queue with one extra of each kind and the flag set, saved as a toggle would save it. */
    private suspend fun saveReorderedShuffledQueue() {
        repository.setUnifiedQueueState(
            activeSlots = slots("s1" to "a", "x" to "c", "s2" to "b", "y" to "d"),
            baselineSlots = slots("s1" to "a", "s2" to "b"),
            currentSlotId = "s1",
            positionMs = 10,
            shuffleEnabled = true,
            repeatMode = 0,
            extras = mapOf("x" to playNext, "y" to addToQueue),
            shuffleReordered = true,
        )
    }

    private suspend fun storedFlag(): Boolean = repository.getQueueSnapshot().cursor?.shuffleReordered == true

    @Test fun theUnifiedWriteStoresTheTagsOnTheLiveRowsAndTheFlagOnTheCursor() = runBlocking {
        saveReorderedShuffledQueue()

        val (snapshot, baseline) = repository.getQueueSnapshotWithBaseline()
        assertEquals(mapOf("x" to playNext, "y" to addToQueue), snapshot.extras)
        assertTrue(snapshot.cursor!!.shuffleReordered)
        assertEquals(listOf("s1", "s2"), baseline.map { it.slotId })
        assertEquals(snapshot, repository.getQueueSnapshot())
        assertEquals(listOf(null, 3L, null, 4L), fx.queueDao.getQueueItems().map { it.extraSequence })
    }

    @Test fun aTrackChangeCursorWriteKeepsTheFlag() = runBlocking {
        saveReorderedShuffledQueue()

        repository.setCursorAlreadyAdmitted("x", 0)

        assertTrue(storedFlag())
        assertEquals("x", repository.getQueueSnapshot().cursor?.currentSlotId)
    }

    @Test fun anOrdinaryQueueWriteWithoutAFlagKeepsTheStoredOne() = runBlocking {
        saveReorderedShuffledQueue()

        repository.setQueueState(slots("s1" to "a", "s2" to "b"), currentSlotId = "s1", positionMs = 20)

        assertTrue(storedFlag())
        val cursor = repository.getQueueSnapshot().cursor!!
        assertTrue(cursor.shuffleEnabled)
        assertEquals(0, cursor.repeatMode)
    }

    @Test fun anOrdinaryQueueWriteStoresTheFlagItIsGivenAndTheTagsOfItsRows() = runBlocking {
        saveReorderedShuffledQueue()

        repository.setQueueState(
            slots("s1" to "a", "y" to "d", "s2" to "b"),
            currentSlotId = "s1",
            positionMs = 20,
            extras = mapOf("y" to addToQueue),
            shuffleReordered = true,
        )
        assertTrue(storedFlag())
        assertEquals(mapOf("y" to addToQueue), repository.getQueueSnapshot().extras)

        repository.setQueueState(slots("s1" to "a"), currentSlotId = "s1", positionMs = 20, shuffleReordered = false)
        assertFalse(storedFlag())
        assertTrue(repository.getQueueSnapshot().extras.isEmpty())
    }

    @Test fun theShuffleModeWriteKeepsTheFlagAndItsFirstRowStartsWithoutOne() = runBlocking {
        repository.setShuffleEnabledAlreadyAdmitted(true)
        assertFalse("no cursor row existed, so there was no flag to keep", storedFlag())

        saveReorderedShuffledQueue()
        repository.setShuffleEnabledAlreadyAdmitted(true)
        assertTrue(storedFlag())
    }

    @Test fun theRepeatModeWriteKeepsTheFlagAndItsFirstRowStartsWithoutOne() = runBlocking {
        repository.setRepeatModeAlreadyAdmitted(2)
        assertFalse("no cursor row existed, so there was no flag to keep", storedFlag())

        saveReorderedShuffledQueue()
        repository.setRepeatModeAlreadyAdmitted(1)
        assertTrue(storedFlag())
        assertEquals(1, repository.getQueueSnapshot().cursor?.repeatMode)
    }

    @Test fun aUnifiedWriteWithTheFlagClearedClearsItAndDropsTheTags() = runBlocking {
        saveReorderedShuffledQueue()

        repository.setUnifiedQueueState(
            slots("s1" to "a", "x" to "c", "s2" to "b", "y" to "d"),
            slots("s1" to "a", "x" to "c", "s2" to "b", "y" to "d"),
            "s1",
            10,
            false,
            0,
        )

        assertFalse(storedFlag())
        assertTrue(repository.getQueueSnapshot().extras.isEmpty())
    }

    @Test fun deletingASongTakesItsTaggedRowWithIt() = runBlocking {
        saveReorderedShuffledQueue()

        fx.songDao.deleteSongById("c")

        assertEquals(mapOf("y" to addToQueue), repository.getQueueSnapshot().extras)
    }
}
