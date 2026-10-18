package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.Song
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The resume-point saves the engine makes while playing: a pause that did not come from the app
 * (the system, a headset, another controller), a seek inside the current song, a change of track,
 * and the full save the app asks for when it goes to the background. Reads the recorded saves.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCheckpointInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    /** Plays [list] from [startIndex] until audible, then forgets the saves the load made. */
    private fun playAndClear(list: List<Song>, startIndex: Int = 0) {
        playQueueAndAwait(list, startIndex)
        await("playing") { onMain { extraController.isPlaying } }
        await("load written") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        settle()
        recorder.writes.clear()
    }

    /**
     * Pauses from outside and forgets that save. While playing, a seek or skip briefly stops
     * playback (buffering), and that stop saves too; paused, only the save under test can.
     */
    private fun pauseAndClear() {
        onMain { extraController.pause() }
        await("paused") { onMain { !extraController.isPlaying } }
        settle()
        recorder.writes.clear()
    }

    private fun currentSlotId() = onMain { engine.currentSlot()!!.slotId }

    @Test fun aPauseFromOutsideTheAppSavesTheResumePoint() {
        playAndClear(songs(3, "outside"))
        val slot = currentSlotId()

        onMain { extraController.pause() }

        await("cursor saved") { recorder.writesOf<QueueWrite.Cursor>().any { it.currentSlotId == slot } }
    }

    @Test fun aSeekInsideTheCurrentSongSavesTheNewPosition() {
        playAndClear(songs(3, "seek"))
        pauseAndClear()
        val slot = currentSlotId()

        onMain { extraController.seekTo(20_000L) }

        await("cursor saved near 20 s") {
            recorder.writesOf<QueueWrite.Cursor>().any { it.currentSlotId == slot && abs(it.positionMs - 20_000L) < 1_000L }
        }
    }

    @Test fun aTrackChangeSavesTheNewTrackAsTheResumePoint() {
        playAndClear(songs(3, "track"))
        pauseAndClear()
        val next = onMain { engine.queue.now()[1].slotId }

        onMain { engine.next() }

        await("cursor saved on the next track") { recorder.writesOf<QueueWrite.Cursor>().any { it.currentSlotId == next } }
    }

    /** The backgrounding save writes the full order, not the ~51 loaded items. */
    @Test fun theBackgroundingSaveWritesTheFullQueueAndCursor() {
        val list = songs(60, "bg")
        playAndClear(list, startIndex = 2)
        val slot = currentSlotId()

        onMain { engine.persistPlaybackState() }

        await("queue saved") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        val save = recorder.writesOf<QueueWrite.QueueState>().single()
        assertEquals(list.map { it.id }, save.slots.map { it.songId })
        assertEquals(slot, save.currentSlotId)
    }

    /** The in-app pause saves the full queue as well as the cursor. */
    @Test fun anInAppPauseSavesTheFullQueue() {
        val list = songs(60, "pause")
        playAndClear(list)

        onMain { engine.pause() }

        await("queue saved") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        assertEquals(list.map { it.id }, recorder.writesOf<QueueWrite.QueueState>().single().slots.map { it.songId })
    }
}
