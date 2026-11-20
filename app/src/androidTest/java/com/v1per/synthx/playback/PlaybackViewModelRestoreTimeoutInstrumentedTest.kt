package com.v1per.synthx.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.SettableFuture
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A restore held up past the wait bound (its session connection is held open): the waiting Play
 * Next falls back to playing the song alone, and when the connection finally lands the late
 * restore does not replace that queue with the saved one.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackViewModelRestoreTimeoutInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    override val createEngineInSetUp = false

    @Test fun aLateRestoreNeverReplacesTheQueueStartedAfterTheBound() {
        val saved = songs(4, "late").mapIndexed { i, song -> QueueSlot("late-slot-$i", song) }
        recorder.snapshot = QueueSnapshot(saved, 4, QueueCursor("late-slot-1", 0L, false, Player.REPEAT_MODE_OFF))
        recorder.baseline = saved
        val held = SettableFuture.create<MediaController>()
        val heldListener = AtomicReference<MediaController.Listener>()
        engine = newEngine { listener ->
            heldListener.set(listener)
            held
        }
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine), restoreWaitTimeoutMs = 500L) }
        val x = song("late-x")

        onMain {
            viewModel.restoreSavedQueue()
            viewModel.playNext(x)
        }
        await("the wait bound passed") { onMain { viewModel.queueRestoreState.value } == QueueRestoreState.Done }
        SystemClock.sleep(300)
        assertTrue("nothing is installed before a controller exists", onMain { engine.orderStore.isEmpty })

        onMain { held.setFuture(realConnection(heldListener.get())) }

        await("x playing alone") { onMain { engine.queue.now().songIds() } == listOf("late-x") }
        settle()
        assertEquals(listOf("late-x"), onMain { engine.queue.now().songIds() })
        assertEquals(listOf("late-x"), recorder.writesOf<QueueWrite.Unified>().last().activeSlots.map { it.songId })
    }
}
