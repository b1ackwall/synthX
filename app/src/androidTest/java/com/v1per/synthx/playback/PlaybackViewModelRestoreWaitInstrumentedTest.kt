package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Play Next issued while the launch restore of five saved songs is still running ends up after
 * the current song of the restored queue: it neither replaces the saved queue with a one-song queue
 * nor is lost.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackViewModelRestoreWaitInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    private val saved by lazy { songs(5, "wait").mapIndexed { i, song -> QueueSlot("wait-slot-$i", song) } }

    @Test fun aPlayNextDuringTheRestoreLandsAfterTheCurrentSongOfTheSavedQueue() {
        recorder.snapshot = QueueSnapshot(saved, 5, QueueCursor("wait-slot-2", 3_000L, false, Player.REPEAT_MODE_OFF))
        recorder.baseline = saved
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }
        val x = song("wait-x")

        val result = onMain {
            viewModel.restoreSavedQueue()
            viewModel.playNext(x)
        }

        assertEquals(QueueAddResult.Added, result)
        await("the saved queue plus x") { onMain { engine.queue.now().size } == 6 }
        settle()
        assertEquals(
            listOf("wait-0", "wait-1", "wait-2", "wait-x", "wait-3", "wait-4"),
            onMain { engine.queue.now().songIds() },
        )
        assertEquals("wait-slot-2", onMain { engine.currentSlot()?.slotId })
        assertEquals(
            "the saved queue is never replaced by a one-song queue",
            emptyList<QueueWrite.Unified>(),
            recorder.writesOf<QueueWrite.Unified>(),
        )
    }

    @Test fun anAddToQueueDuringTheRestoreLandsAtTheEndOfTheSavedQueue() {
        recorder.snapshot = QueueSnapshot(saved, 5, QueueCursor("wait-slot-0", 0L, false, Player.REPEAT_MODE_OFF))
        recorder.baseline = saved
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }

        onMain {
            viewModel.restoreSavedQueue()
            viewModel.addToQueue(song("wait-y"))
        }

        await("the saved queue plus y") { onMain { engine.queue.now().size } == 6 }
        settle()
        assertEquals("wait-y", onMain { engine.queue.now().last().song.id })
    }
}
