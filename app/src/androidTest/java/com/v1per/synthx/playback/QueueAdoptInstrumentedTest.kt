package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An engine that adopts a session still holding a timeline, when the playing slot's song row is gone
 * (its queue rows went with it): the cursor moves to the slot the cold restore would start on, the
 * window is rebuilt there at 0 ms, and it plays only when the session was playing. With no surviving
 * slot nothing is adopted. Also: the restore's release of every engine leaves no loaded timeline
 * behind a null cursor.
 */
@RunWith(AndroidJUnit4::class)
class QueueAdoptInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    /**
     * Plays five songs from the middle one, then releases that engine: the extra controller keeps
     * the session owned, so the timeline keeps playing (or stays paused) with no engine behind it.
     */
    private fun handOver(paused: Boolean): List<QueueSlot> {
        playQueueAndAwait(songs(5, "adopt"), 2)
        await("playing") { onMain { extraController.isPlaying } }
        if (paused) {
            onMain { engine.pause() }
            await("paused") { onMain { !extraController.isPlaying } }
        }
        val live = onMain { engine.queue.now() }
        releaseEngine(engine)
        return live
    }

    /** The saved queue without the playing slot's rows, as after its song row was deleted. */
    private fun rowGoneRead(live: List<QueueSlot>, playing: QueueSlot): Pair<QueueSnapshot, List<QueueSlot>> {
        val survivors = live.filter { it.slotId != playing.slotId }
        val positions = live.mapIndexed { index, slot -> slot.slotId to index }.toMap()
        val snapshot = QueueSnapshot(
            slots = survivors,
            storedSlotCount = survivors.size,
            cursor = QueueCursor(playing.slotId, 12_000L, shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF),
            storedPositions = positions.filterKeys { it != playing.slotId },
            currentQueuePosition = positions.getValue(playing.slotId),
        )
        return snapshot to survivors
    }

    private fun adopt(): ExoPlayerEngine {
        val adopter = newEngine()
        await("the adopting engine connected") { onMain { adopter.connection.isConnected } }
        settle()
        return adopter
    }

    private fun sessionIds(): List<String> =
        onMain { (0 until extraController.mediaItemCount).map { extraController.getMediaItemAt(it).mediaId } }

    @Test fun aPlayingSlotWhoseSongRowIsGoneMovesToTheRestoreStartAndKeepsPlaying() {
        val live = handOver(paused = false)
        recorder.adoptionRead = rowGoneRead(live, live[2])

        val adopter = adopt()

        // The first survivor stored at or after the deleted slot's position: the old slot 3.
        val start = live[3].slotId
        assertEquals("cursor on the restore start", start, onMain { adopter._currentSlotId.value })
        assertEquals("the session plays the start slot", start, onMain { extraController.currentMediaItem?.mediaId })
        assertEquals(
            "the window holds the survivors only",
            live.filter {
                it != live[2]
            }.map { it.slotId },
            sessionIds(),
        )
        await("still playing") { onMain { extraController.isPlaying } }
        assertTrue("from the start of the song", onMain { extraController.currentPosition } < 10_000L)
        assertEquals("Up Next", listOf(live[4].slotId), onMain { adopter._upcomingQueue.value.map { it.slotId } })
        assertTrue("Next is available", onMain { adopter._hasNext.value })
        assertEquals(live[3].song.id, onMain { adopter._currentSong.value?.id })
    }

    @Test fun aPausedSessionWhoseSongRowIsGoneMovesToTheRestoreStartAndStaysPaused() {
        val live = handOver(paused = true)
        recorder.adoptionRead = rowGoneRead(live, live[2])

        val adopter = adopt()

        assertEquals(live[3].slotId, onMain { adopter._currentSlotId.value })
        assertEquals(live[3].slotId, onMain { extraController.currentMediaItem?.mediaId })
        assertFalse("stays paused", onMain { extraController.isPlaying })
        assertFalse("play-when-ready stays off", onMain { extraController.playWhenReady })
    }

    @Test fun withNoSurvivingSlotNothingIsAdopted() {
        val live = handOver(paused = false)
        recorder.adoptionRead = QueueSnapshot(
            slots = emptyList(),
            storedSlotCount = 1,
            cursor = QueueCursor(live[2].slotId, 0L, shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF),
            currentQueuePosition = 2,
        ) to emptyList()

        val adopter = adopt()

        assertTrue("nothing adopted", onMain { adopter.orderStore.isEmpty })
        assertNull("no current song", onMain { adopter._currentSong.value })
    }

    @Test fun releasingEveryEngineForARestoreClearsTheSessionTimeline() {
        playQueueAndAwait(songs(3, "restore"), 0)
        await("playing") { onMain { extraController.isPlaying } }

        runBlocking { ExoPlayerEngine.releaseAllForRestore() }
        markReleased(engine)

        await("the session timeline is cleared") { onMain { extraController.mediaItemCount == 0 } }
        onMain { extraController.play() }
        settle()
        assertEquals("a system Play finds nothing to resume", 0, onMain { extraController.mediaItemCount })
        assertFalse(onMain { extraController.isPlaying })
    }
}
