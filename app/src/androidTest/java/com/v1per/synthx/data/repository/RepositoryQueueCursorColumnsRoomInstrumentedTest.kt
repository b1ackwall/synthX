package com.v1per.synthx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every write that rebuilds or touches the cursor row, against the real tables: the customised-queue
 * flag is set only where a write is given one and kept everywhere else, and the saved queue position
 * is always the current slot's row position (kept while that slot is unchanged and its row is gone).
 */
@RunWith(AndroidJUnit4::class)
class RepositoryQueueCursorColumnsRoomInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var fx: RepositoryTestFixture
    private val repository get() = fx.repository

    @Before fun setUp() = runBlocking {
        fx = RepositoryTestFixture(ApplicationProvider.getApplicationContext())
        listOf("a", "b", "c", "d").forEach { fx.song(it, title = "Song $it") }
    }

    @After fun tearDown() = fx.close()

    private fun slots(vararg pairs: Pair<String, String>) = pairs.map { (slot, song) -> QueueSlotRecord(slot, song) }

    private val abc = slots("s1" to "a", "s2" to "b", "s3" to "c")

    private suspend fun snapshot() = repository.getQueueSnapshot()

    private suspend fun flag() = snapshot().cursor!!.queueCustomized

    private suspend fun position() = snapshot().currentQueuePosition

    @Test fun aQueueWriteSavesTheCurrentRowsPositionAndEveryRowsPosition() = runBlocking {
        repository.setQueueState(abc, currentSlotId = "s2", positionMs = 10)

        assertEquals(1, position())
        assertEquals(mapOf("s1" to 0, "s2" to 1, "s3" to 2), snapshot().storedPositions)
    }

    @Test fun thePositionFollowsAnInsertBeforeTheCurrentSlotAndAMove() = runBlocking {
        repository.setQueueState(abc, currentSlotId = "s2", positionMs = 10)
        repository.setQueueState(slots("s0" to "d") + abc, currentSlotId = "s2", positionMs = 10)
        assertEquals("one slot inserted before it", 2, position())

        repository.setQueueState(slots("s2" to "b", "s0" to "d", "s1" to "a", "s3" to "c"), "s2", 10)
        assertEquals("moved to the front", 0, position())
    }

    @Test fun aTrackChangeKeepsTheFlagsAndFollowsTheNewSlot() = runBlocking {
        repository.setQueueState(abc, "s1", 10, shuffleReordered = true, queueCustomized = true)

        repository.setCursorAlreadyAdmitted("s3", 500)

        assertTrue(flag())
        assertTrue(snapshot().cursor!!.shuffleReordered)
        assertEquals(2, position())
    }

    @Test fun aQueueWriteWithoutAFlagKeepsTheStoredOne() = runBlocking {
        repository.setQueueState(abc, "s1", 10, queueCustomized = true)

        repository.setQueueState(abc, "s1", 10)

        assertTrue(flag())
    }

    @Test fun aQueueWriteStoresTheFlagItIsGivenEitherWay() = runBlocking {
        repository.setQueueState(abc, "s1", 10, queueCustomized = true)
        assertTrue(flag())

        repository.setQueueState(abc, "s1", 10, queueCustomized = false)
        assertFalse(flag())
    }

    @Test fun theUnifiedWriteStoresTheFlagAsGivenWithThePosition() = runBlocking {
        repository.setUnifiedQueueState(abc, abc, "s3", 10, true, 2, queueCustomized = true)
        assertTrue(flag())
        assertEquals(2, position())

        repository.setUnifiedQueueState(abc, abc, "s1", 10, false, 0, queueCustomized = false)
        assertFalse(flag())
        assertEquals(0, position())
    }

    @Test fun theModeWritesKeepTheFlagAndThePosition() = runBlocking {
        repository.setQueueState(abc, "s2", 10, queueCustomized = true)

        repository.setShuffleEnabledAlreadyAdmitted(true)
        repository.setRepeatModeAlreadyAdmitted(2)

        assertTrue(flag())
        assertEquals(1, position())
    }

    @Test fun theFirstRepeatWriteStartsTheCursorUntouchedWithNoPosition() = runBlocking {
        repository.setRepeatModeAlreadyAdmitted(1)

        assertFalse(flag())
        assertNull(position())
        assertEquals(1, snapshot().cursor!!.repeatMode)
    }

    @Test fun theFirstShuffleWriteStartsTheCursorUntouchedWithNoPosition() = runBlocking {
        repository.setShuffleEnabledAlreadyAdmitted(true)

        assertFalse(flag())
        assertNull(position())
        assertTrue(snapshot().cursor!!.shuffleEnabled)
    }

    @Test fun aCascadeKeepsTheSavedPositionAndACursorWriteOnTheSameSlotKeepsItToo() = runBlocking {
        repository.setQueueState(abc, "s2", 10, queueCustomized = true)
        fx.songDao.deleteSongById("b")

        assertEquals("the cascade leaves a gap", mapOf("s1" to 0, "s3" to 2), snapshot().storedPositions)
        assertEquals(1, position())

        // An exit checkpoint that still names the deleted slot.
        repository.setCursorAlreadyAdmitted("s2", 900)

        assertEquals(1, position())
        assertTrue(flag())
    }

    @Test fun aCursorWriteToASlotWithNoRowAndADifferentSlotSavesNoPosition() = runBlocking {
        repository.setQueueState(abc, "s2", 10)

        repository.setCursorAlreadyAdmitted("ghost", 0)

        assertNull(position())
    }

    @Test fun aCursorWriteWithNoCurrentSlotSavesNoPosition() = runBlocking {
        repository.setQueueState(abc, "s2", 10, queueCustomized = true)

        repository.setQueueState(abc, null, 0)

        assertNull(position())
        assertTrue(flag())
    }
}
