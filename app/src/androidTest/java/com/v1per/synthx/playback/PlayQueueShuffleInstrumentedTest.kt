package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * playQueue's explicit shuffle choice: the flag, the live/baseline orders and the persisted state
 * are set in one step, from a cold engine (nothing loaded, no current slot) as well as over an
 * existing queue. Writes go to a recording repository so the number of commits can be counted.
 */
@RunWith(AndroidJUnit4::class)
class PlayQueueShuffleInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    /** Every queue write the engine made except cursor-only checkpoints. */
    private val structuralWrites: List<QueueWrite>
        get() = recorder.writes.filterNot { it is QueueWrite.Cursor }

    /** The shuffle flag carried by each unified write, in order. */
    private val unifiedShuffleFlags: List<Boolean>
        get() = recorder.writesOf<QueueWrite.Unified>().map { it.shuffleEnabled }

    @Test fun aColdShuffledPlayStartsOnTheChosenSongWithTheFlagOnInOneWrite() {
        assertNull("precondition: nothing loaded", onMain { engine._currentSlotId.value })
        assertEquals("precondition: shuffle off", false, onMain { engine._shuffleEnabled.value })
        val list = songs(6, "cold")

        playAndAwait(list, startIndex = 3, shuffle = true)

        onMain {
            assertTrue(engine._shuffleEnabled.value)
            assertEquals("cold-3", engine.orderStore.live.first().songId)
            assertEquals(list.map { it.id }, engine.orderStore.baseline.songIds())
            assertEquals(list.map { it.id }.toSet(), engine.orderStore.live.songIds().toSet())
        }
        assertOneUnifiedWrite(shuffle = true)
    }

    @Test fun anUnshuffledPlayOverAShuffledQueueTurnsTheFlagOffInListOrder() {
        playAndAwait(songs(5, "before"), startIndex = 2, shuffle = true)
        awaitWrites(1)
        settle()
        recorder.writes.clear()
        val list = songs(5, "after")

        playAndAwait(list, startIndex = 0, shuffle = false)

        onMain {
            assertEquals(false, engine._shuffleEnabled.value)
            assertEquals(list.map { it.id }, engine.orderStore.live.songIds())
            assertEquals(list.map { it.id }, engine.orderStore.baseline.songIds())
        }
        assertOneUnifiedWrite(shuffle = false)
    }

    @Test fun aNullChoiceKeepsFollowingTheCurrentFlag() {
        playAndAwait(songs(4, "on"), startIndex = 0, shuffle = true)
        val list = songs(5, "follow")

        playAndAwait(list, startIndex = 1, shuffle = null)

        onMain {
            assertTrue(engine._shuffleEnabled.value)
            assertEquals("follow-1", engine.orderStore.live.first().songId)
            assertEquals(list.map { it.id }, engine.orderStore.baseline.songIds())
        }
    }

    @Test fun turningShuffleOffAfterAShuffledPlayRestoresListOrderAfterTheCurrentSong() {
        val list = songs(6, "restore")
        playAndAwait(list, startIndex = 2, shuffle = true)

        runBlocking { engine.setShuffleEnabled(false) }

        onMain {
            assertEquals(false, engine._shuffleEnabled.value)
            assertEquals(
                listOf("restore-2", "restore-3", "restore-4", "restore-5"),
                engine.orderStore.live.songIds(),
            )
        }
    }

    private fun playAndAwait(list: List<Song>, startIndex: Int, shuffle: Boolean?) {
        runBlocking { engine.playQueue(list, startIndex, shuffle) }
        val expected = list[startIndex].id
        await("$expected playing") {
            onMain {
                val slot = engine._currentSlotId.value
                slot != null && engine.songsBySlotId[slot]?.id == expected &&
                    extraController.currentMediaItem?.mediaId == slot
            }
        }
    }

    private fun awaitWrites(count: Int) = await("$count unified write(s)") { unifiedShuffleFlags.size >= count }

    /** Exactly one structural write — the unified one — carrying [shuffle]. */
    private fun assertOneUnifiedWrite(shuffle: Boolean) {
        awaitWrites(1)
        settle()
        assertEquals(listOf("Unified"), structuralWrites.map { it.javaClass.simpleName })
        assertEquals(listOf(shuffle), unifiedShuffleFlags)
    }

    private fun List<QueueSlotRecord>.songIds() = map { it.songId }
}
