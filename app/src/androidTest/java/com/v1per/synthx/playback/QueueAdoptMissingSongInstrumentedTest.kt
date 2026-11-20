package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An engine that adopts a playing session whose playing slot's song is flagged missing: the saved
 * read drops that slot from the surviving order, and the engine puts it back at its stored position,
 * from its saved row, instead of leaving the cursor outside the order. Playback is never interrupted;
 * the mini player, Next and Up Next work, and the next save names the slot.
 */
@RunWith(AndroidJUnit4::class)
class QueueAdoptMissingSongInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    private class HandedOver(val live: List<QueueSlot>, val unshuffled: List<QueueSlot>, val positionMs: Long)

    /** Plays five songs from the middle one, optionally shuffled, then releases that engine while it plays. */
    private fun handOver(shuffle: Boolean): HandedOver {
        playQueueAndAwait(songs(5, "missing"), 2)
        val unshuffled = onMain { engine.queue.now() }
        if (shuffle) runBlocking { engine.setShuffleEnabled(true) }
        await("playing") { onMain { extraController.isPlaying } }
        val live = onMain { engine.queue.now() }
        releaseEngine(engine)
        return HandedOver(live, unshuffled, onMain { extraController.currentPosition })
    }

    /** The saved read as the database gives it: the playing slot dropped, but its row still there. */
    private fun missingRead(handed: HandedOver, shuffle: Boolean): Pair<QueueSnapshot, List<QueueSlot>> {
        val playing = handed.live[2]
        val snapshot = QueueSnapshot(
            slots = handed.live.filter { it.slotId != playing.slotId },
            storedSlotCount = handed.live.size,
            cursor = QueueCursor(playing.slotId, handed.positionMs, shuffle, Player.REPEAT_MODE_OFF),
            storedPositions = handed.live.mapIndexed { index, slot -> slot.slotId to index }.toMap(),
            currentQueuePosition = 2,
            missingSlots = listOf(QueueSlot(playing.slotId, playing.song.copy(isMissing = true))),
        )
        val baseline = (if (shuffle) handed.unshuffled else handed.live).filter { it.slotId != playing.slotId }
        return snapshot to baseline
    }

    private fun adopt(): ExoPlayerEngine {
        val adopter = newEngine()
        await("the adopting engine connected") { onMain { adopter.connection.isConnected } }
        settle()
        return adopter
    }

    private fun ExoPlayerEngine.liveIds() = onMain { orderStore.live.map { it.slotId } }

    @Test fun shuffleOffTheMissingPlayingSlotIsAdoptedAtItsPlaceAndKeepsPlaying() {
        val handed = handOver(shuffle = false)
        recorder.adoptionRead = missingRead(handed, shuffle = false)
        val playing = handed.live[2]

        val adopter = adopt()

        assertEquals("adopted at its stored position", handed.live.map { it.slotId }, adopter.liveIds())
        assertEquals(
            "baseline matches live",
            adopter.liveIds(),
            onMain {
                adopter.orderStore.baseline.map { it.slotId }
            },
        )
        assertEquals(playing.slotId, onMain { adopter._currentSlotId.value })
        assertEquals("the mini player shows the song", playing.song.id, onMain { adopter._currentSong.value?.id })
        assertEquals(playing.slotId, onMain { extraController.currentMediaItem?.mediaId })
        assertTrue("never paused", onMain { extraController.isPlaying })
        assertTrue("never restarted", onMain { extraController.currentPosition } >= handed.positionMs)
        assertEquals(
            "Up Next",
            handed.live.drop(3).map { it.slotId },
            onMain { adopter._upcomingQueue.value.map { it.slotId } },
        )
        assertTrue("Next is available", onMain { adopter._hasNext.value })

        onMain { adopter.pause() }
        await("the pause saved the queue") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        settle()
        val saved = recorder.writesOf<QueueWrite.QueueState>().last()
        assertEquals("the save names the adopted slot", playing.slotId, saved.currentSlotId)
        assertEquals("and keeps it in its place", handed.live.map { it.slotId }, saved.slots.map { it.slotId })

        onMain { adopter.next() }
        await("Next moves on") { onMain { extraController.currentMediaItem?.mediaId == handed.live[3].slotId } }
    }

    /**
     * Shuffle ON: the read's baseline lacks the missing slot, and the repair of a restored shuffled
     * queue rebuilds a baseline that lacks the current slot from the live order. Shuffle OFF then
     * keeps the shuffled order, and the adopted slot keeps its place.
     */
    @Test fun shuffleOnTheMissingPlayingSlotIsAdoptedAndShuffleOffKeepsTheOrder() {
        val handed = handOver(shuffle = true)
        recorder.adoptionRead = missingRead(handed, shuffle = true)
        val playing = handed.live[2]

        val adopter = adopt()

        assertEquals("adopted at its stored position", handed.live.map { it.slotId }, adopter.liveIds())
        assertTrue("shuffle comes back on", onMain { adopter._shuffleEnabled.value })
        assertEquals(
            "the repaired baseline is the live order",
            adopter.liveIds(),
            onMain { adopter.orderStore.baseline.map { it.slotId } },
        )
        assertTrue(onMain { extraController.isPlaying })

        runBlocking { adopter.setShuffleEnabled(false) }

        assertEquals("shuffle OFF keeps the order", handed.live.map { it.slotId }, adopter.liveIds())
        assertEquals(playing.slotId, onMain { adopter._currentSlotId.value })
        assertTrue("still playing", onMain { extraController.isPlaying })
    }
}
