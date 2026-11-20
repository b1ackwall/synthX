package com.v1per.synthx.playback

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.RepositoryTestFixture
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a queue restored from the real tables starts when the saved song no longer resolves: the
 * first surviving slot at or after its saved queue position, or the last one before it, at 0 ms,
 * whether the song is flagged missing (its row kept) or its row was removed by the cascade (a gap
 * in the positions). A cursor saved before the position column starts at the first survivor.
 */
@RunWith(AndroidJUnit4::class)
class QueueRestoreStartRoomInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var fx: RepositoryTestFixture
    private lateinit var persistence: QueuePersistence

    private val ids = listOf("a", "b", "c", "d", "e")

    @Before fun setUp() = runBlocking {
        fx = RepositoryTestFixture(ApplicationProvider.getApplicationContext())
        ids.forEach { fx.song(it, title = "Song $it") }
        persistence = QueuePersistence(fx.repository)
    }

    @After fun tearDown() {
        runBlocking { persistence.quiesceForRestore() }
        fx.close()
    }

    private fun records(order: List<String>) = order.map { QueueSlotRecord("slot-$it", it) }

    private suspend fun saveInOrder(current: String, order: List<String> = ids) {
        fx.repository.setQueueState(records(order), currentSlotId = "slot-$current", positionMs = 9_000L)
    }

    private suspend fun startSlot(): Pair<String, Long> {
        val restored = checkNotNull(persistence.loadQueue()) { "a saved queue must be restored" }
        return restored.slots[restored.startIndex].slotId to restored.positionMs
    }

    @Test fun aSurvivingCurrentSongResumesWhereItWas() = runBlocking {
        saveInOrder(current = "c")
        fx.songDao.markMissing("a", 1L)

        assertEquals("slot-c" to 9_000L, startSlot())
    }

    @Test fun aCurrentSongFlaggedMissingStartsTheNextSurvivorAtZero() = runBlocking {
        saveInOrder(current = "c")
        fx.songDao.markMissing("c", 1L)

        assertEquals("slot-d" to 0L, startSlot())
    }

    @Test fun aCurrentSongWhoseRowWasCascadedAwayStartsTheNextSurvivorAtZero() = runBlocking {
        saveInOrder(current = "c")
        fx.songDao.deleteSongById("c")

        assertEquals(listOf(0, 1, 3, 4), fx.queueDao.getQueueItems().map { it.position })
        assertEquals("slot-d" to 0L, startSlot())
    }

    @Test fun severalMissingSongsAroundTheCurrentOneAreSkipped() = runBlocking {
        saveInOrder(current = "b")
        fx.songDao.deleteSongById("b")
        fx.songDao.markMissing("c", 1L)

        assertEquals("slot-d" to 0L, startSlot())
    }

    @Test fun aMissingLastSongStartsTheLastSurvivorBeforeIt() = runBlocking {
        saveInOrder(current = "e")
        fx.songDao.markMissing("e", 1L)

        assertEquals("slot-d" to 0L, startSlot())
    }

    @Test fun aShuffledQueueFollowsTheSavedLiveOrder() = runBlocking {
        val live = listOf("c", "a", "e", "b", "d")
        fx.repository.setUnifiedQueueState(
            activeSlots = records(live),
            baselineSlots = records(ids),
            currentSlotId = "slot-e",
            positionMs = 9_000L,
            shuffleEnabled = true,
            repeatMode = 0,
        )
        fx.songDao.markMissing("e", 1L)

        assertEquals("slot-b" to 0L, startSlot())
    }

    @Test fun aCursorSavedBeforeThePositionColumnStartsTheFirstSurvivor() = runBlocking {
        saveInOrder(current = "c")
        fx.database.openHelper.writableDatabase.execSQL(
            "UPDATE playback_cursor SET current_queue_position = NULL WHERE id = 0",
        )
        fx.songDao.markMissing("c", 1L)

        assertEquals("slot-a" to 0L, startSlot())
    }

    @Test fun nothingSurvivingRestoresNothing() = runBlocking {
        saveInOrder(current = "c")
        ids.forEach { fx.songDao.markMissing(it, 1L) }

        assertNull(persistence.loadQueue())
    }
}
