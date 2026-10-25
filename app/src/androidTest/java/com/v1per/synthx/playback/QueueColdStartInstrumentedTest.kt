package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.QueueSnapshot
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start queue restore ([ExoPlayerEngine.restoreSavedQueue]) and adoption of a session that is
 * already running, against the real service and engine. The saved queue is served by a
 * [RecordingQueueRepository]; results are read from the public flows, the session (as the system
 * sees it), and the recorded writes.
 */
@RunWith(AndroidJUnit4::class)
class QueueColdStartInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    /** Six saved slots; slot-3's song is gone, so five resolve. The live order is a shuffled one. */
    private fun serveShuffledQueueWithADroppedSong(): List<QueueSlot> {
        val slots = songs(6, "cold").mapIndexed { i, song -> QueueSlot("slot-$i", song) }
        val live = listOf(slots[2], slots[0], slots[4], slots[1], slots[5])
        recorder.snapshot = QueueSnapshot(live, 6, QueueCursor("slot-4", 12_000L, true, Player.REPEAT_MODE_ALL))
        recorder.baseline = listOf(slots[0], slots[1], slots[2], slots[4], slots[5])
        return live
    }

    @Test fun restoresTheSavedQueuePausedAtTheSavedSlotAndPosition() {
        val live = serveShuffledQueueWithADroppedSong()

        runBlocking { engine.restoreSavedQueue() }

        onMain {
            assertEquals("live order", live.slotIds(), engine.queue.now().slotIds())
            assertEquals("index of slot-4 in the live order", 2, engine.currentIndex.now())
            assertEquals("current song", "cold-4", engine.currentSongId.now())
            assertTrue("shuffle flag", engine.shuffleEnabled.now())
            assertEquals("repeat", RepeatMode.All, engine.repeatMode.now())
            assertFalse("a restored queue is not customised", engine.isQueueCustomized.now())
            assertEquals("up next", listOf("slot-1", "slot-5"), engine.upcomingQueue.now().slotIds())
        }
        await("session on slot-4 near 12 s") {
            onMain {
                extraController.currentMediaItem?.mediaId == "slot-4" &&
                    abs(extraController.currentPosition - 12_000L) < 1_000L
            }
        }
        settle()
        assertFalse("restored paused", onMain { extraController.isPlaying })
        assertFalse("not set to play", onMain { extraController.playWhenReady })
        assertEquals("session repeat", Player.REPEAT_MODE_ALL, onMain { extraController.repeatMode })
    }

    /** The shared player is still set to play from earlier use, with nothing loaded. */
    @Test fun restoresPausedWhenThePlayerIsStillSetToPlayOnAnEmptyTimeline() {
        val live = serveShuffledQueueWithADroppedSong()
        onMain { extraController.playWhenReady = true }
        await("player set to play") { onMain { extraController.playWhenReady } }
        assertEquals("precondition: empty timeline", 0, onMain { extraController.mediaItemCount })

        runBlocking { engine.restoreSavedQueue() }

        await("session on slot-4 near 12 s") {
            onMain {
                extraController.currentMediaItem?.mediaId == "slot-4" &&
                    abs(extraController.currentPosition - 12_000L) < 1_000L
            }
        }
        settle()
        assertEquals("restored queue order", live.slotIds(), onMain { engine.queue.now().slotIds() })
        assertFalse("restored paused", onMain { extraController.isPlaying })
        assertFalse("not set to play", onMain { extraController.playWhenReady })
        assertFalse("engine reports not playing", onMain { engine.isPlaying.now() })
    }

    @Test fun aDroppedSongRewritesTheSavedRowsOnceFromTheFullOrder() {
        val live = serveShuffledQueueWithADroppedSong()

        runBlocking { engine.restoreSavedQueue() }
        await("rewrite of the saved rows") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        settle()

        assertEquals(
            listOf(QueueWrite.QueueState(live.map { QueueSlotRecord(it.slotId, it.song.id) }, "slot-4", 12_000L)),
            recorder.writesOf<QueueWrite.QueueState>(),
        )
        assertTrue("restore never writes the baseline", recorder.writesOf<QueueWrite.Baseline>().isEmpty())
    }

    @Test fun nothingDroppedMeansNoRewrite() {
        val slots = songs(3, "whole").mapIndexed { i, song -> QueueSlot("whole-$i", song) }
        recorder.snapshot = QueueSnapshot(slots, 3, QueueCursor("whole-1", 0L, false, Player.REPEAT_MODE_OFF))
        recorder.baseline = slots

        runBlocking { engine.restoreSavedQueue() }
        await("session on whole-1") { onMain { extraController.currentMediaItem?.mediaId == "whole-1" } }
        settle()

        assertTrue(recorder.writes.toString(), recorder.writesOf<QueueWrite.QueueState>().isEmpty())
        assertFalse("shuffle flag", onMain { engine.shuffleEnabled.now() })
    }

    /** The baseline read is what shuffle-off rebuilds from: the current slot, then baseline after it. */
    @Test fun theRestoredBaselineIsWhatTurningShuffleOffFollows() {
        serveShuffledQueueWithADroppedSong()
        runBlocking { engine.restoreSavedQueue() }
        await("session on slot-4") { onMain { extraController.currentMediaItem?.mediaId == "slot-4" } }

        runBlocking { engine.setShuffleEnabled(false) }

        assertEquals(listOf("slot-4", "slot-5"), onMain { engine.queue.now().slotIds() })
        await("unified write") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        val unified = recorder.writesOf<QueueWrite.Unified>().single()
        assertEquals(listOf("slot-4", "slot-5"), unified.baselineSlots.map { it.slotId })
        assertFalse(unified.shuffleEnabled)
    }

    @Test fun nothingSavedLeavesTheEngineEmptyAndWritesNothing() {
        runBlocking { engine.restoreSavedQueue() }
        settle()

        assertTrue(onMain { engine.queue.now() }.isEmpty())
        assertEquals(0, onMain { extraController.mediaItemCount })
        assertTrue(recorder.writes.toString(), recorder.writes.isEmpty())
    }

    @Test fun aRunningSessionIsNotReplacedByTheSavedQueue() {
        val saved = songs(3, "saved").mapIndexed { i, song -> QueueSlot("saved-$i", song) }
        recorder.snapshot = QueueSnapshot(saved, 3, QueueCursor("saved-1", 0L, false, Player.REPEAT_MODE_OFF))
        recorder.baseline = saved
        // Adoption finds nothing saved, so only the restore call could bring the saved queue in.
        recorder.adoptionRead = QueueSnapshot(emptyList(), 0, null) to emptyList()
        playQueueAndAwait(songs(3, "running"), 1)
        await("playing") { onMain { extraController.isPlaying } }
        val running = onMain { extraController.currentMediaItem?.mediaId }

        val second = newEngine()
        await("second engine connected to the playing session") { onMain { second.isPlaying.now() } }
        runBlocking { second.restoreSavedQueue() }
        settle()

        assertEquals(running, onMain { extraController.currentMediaItem?.mediaId })
        assertTrue(onMain { second.queue.now() }.none { it.slotId.startsWith("saved-") })
    }

    /**
     * A new engine connecting to a session that is already playing takes its queue from the saved
     * snapshot, not from the ~51 items the player holds: 60 saved songs, shuffle on.
     */
    @Test fun adoptingARunningSessionLoadsTheFullSavedQueue() {
        val list = songs(60, "adopt")
        runBlocking { engine.playQueue(list, 5, true) }
        await("first unified write") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        val written = recorder.writesOf<QueueWrite.Unified>().first()
        val byId = list.associateBy { it.id }
        recorder.snapshot = QueueSnapshot(
            written.activeSlots.map { QueueSlot(it.slotId, byId.getValue(it.songId)) },
            60,
            QueueCursor(written.currentSlotId, 0L, true, Player.REPEAT_MODE_OFF),
        )
        recorder.baseline = written.baselineSlots.map { QueueSlot(it.slotId, byId.getValue(it.songId)) }
        await("session on the chosen song") {
            onMain { extraController.currentMediaItem?.mediaId } ==
                written.currentSlotId
        }
        assertTrue("precondition: the player holds only a window", onMain { extraController.mediaItemCount } < 60)

        val second = newEngine()

        await("adopted the full queue") { onMain { second.queue.now().size } == 60 }
        onMain {
            assertEquals(written.activeSlots.map { it.slotId }, second.queue.now().slotIds())
            assertTrue("shuffle flag from the snapshot", second.shuffleEnabled.now())
            assertEquals("adopted under the playing slot", written.currentSlotId, second.currentSlot()?.slotId)
        }
    }
}
