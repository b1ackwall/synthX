package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine side of a restore whose saved song is gone: it comes up paused on the chosen slot at
 * the start of the song, and the rewrite for the dropped rows saves that slot as the cursor.
 */
@RunWith(AndroidJUnit4::class)
class QueueRestoreStartInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    /** Saved slots a..e at positions 0..4 in [order]; [gone] no longer resolves. */
    private fun serve(order: List<String>, gone: String, current: String, position: Int?, shuffle: Boolean) {
        val songsById = songs(5, "rs").associateBy { it.id.removePrefix("rs-") }
        val all = order.map { QueueSlot("slot-$it", songsById.getValue(it)) }
        recorder.snapshot = QueueSnapshot(
            slots = all.filterNot { it.slotId == "slot-$gone" },
            storedSlotCount = all.size,
            cursor = QueueCursor("slot-$current", 30_000L, shuffle, Player.REPEAT_MODE_OFF),
            storedPositions = all.mapIndexed { index, slot -> slot.slotId to index }.toMap(),
            currentQueuePosition = position,
        )
        recorder.baseline = all.filterNot { it.slotId == "slot-$gone" }
    }

    private fun restoreAndAwait(expectedSlot: String) {
        runBlocking { engine.restoreSavedQueue() }
        await("session on $expectedSlot") { onMain { extraController.currentMediaItem?.mediaId == expectedSlot } }
        await("rewrite for the dropped song") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        settle()
    }

    @Test fun aMissingCurrentSongRestartsAtTheNextSongAndSavesThatAsTheCursor() {
        serve(listOf("0", "1", "2", "3", "4"), gone = "2", current = "2", position = 2, shuffle = false)

        restoreAndAwait("slot-3")

        assertEquals("slot-3", onMain { engine.currentSlot()?.slotId })
        assertTrue("from the start of the song", onMain { abs(extraController.currentPosition) < 1_000L })
        assertTrue("paused", onMain { !extraController.playWhenReady })
        val rewrite = recorder.writesOf<QueueWrite.QueueState>().single()
        assertEquals("slot-3", rewrite.currentSlotId)
        assertEquals(0L, rewrite.positionMs)
        assertEquals(listOf("slot-0", "slot-1", "slot-3", "slot-4"), rewrite.slots.map { it.slotId })
    }

    @Test fun aMissingLastSongRestartsAtTheSongBeforeIt() {
        serve(listOf("0", "1", "2", "3", "4"), gone = "4", current = "4", position = 4, shuffle = false)

        restoreAndAwait("slot-3")

        assertEquals("slot-3", recorder.writesOf<QueueWrite.QueueState>().single().currentSlotId)
    }

    @Test fun aShuffledQueueRestartsAtTheNextSongInItsPlayingOrder() {
        serve(listOf("2", "0", "4", "1", "3"), gone = "4", current = "4", position = 2, shuffle = true)

        restoreAndAwait("slot-1")

        assertEquals(listOf("slot-2", "slot-0", "slot-1", "slot-3"), onMain { engine.queue.now().slotIds() })
        assertTrue(onMain { engine.shuffleEnabled.now() })
    }

    @Test fun aCursorWithoutASavedPositionRestartsAtTheFirstSong() {
        serve(listOf("0", "1", "2", "3", "4"), gone = "2", current = "2", position = null, shuffle = false)

        restoreAndAwait("slot-0")

        assertEquals("slot-0", recorder.writesOf<QueueWrite.QueueState>().single().currentSlotId)
    }
}
