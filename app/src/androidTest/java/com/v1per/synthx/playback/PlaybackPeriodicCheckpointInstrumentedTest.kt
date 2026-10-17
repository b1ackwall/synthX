package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SLOW (about 35 s): the engine's 30-second resume-point save while one song plays uninterrupted.
 * The interval is a fixed 30 s in the app with no clock a test can drive, so this waits it out.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class PlaybackPeriodicCheckpointInstrumentedTest : PlaybackServiceTestBase() {

    // Waits out the real 30 s interval, so the default 60 s limit leaves too little margin.
    override val timeoutSeconds: Long get() = 90L

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    @Test fun uninterruptedPlaybackSavesTheResumePointEveryThirtySeconds() {
        playQueueAndAwait(songs(2, "tick"), 0)
        await("playing") { onMain { extraController.isPlaying } }
        await("load written") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        val slot = onMain { engine.currentSlot()!!.slotId }
        settle()
        recorder.writes.clear()

        await("periodic save", timeoutMs = PERIODIC_CHECKPOINT_INTERVAL_MS + 5_000L) {
            recorder.writesOf<QueueWrite.Cursor>().isNotEmpty()
        }

        val save = recorder.writesOf<QueueWrite.Cursor>().first()
        assertEquals(slot, save.currentSlotId)
        assertEquals("still playing, so nothing else caused the save", true, onMain { extraController.isPlaying })
    }
}
