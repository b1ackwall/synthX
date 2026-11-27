package com.v1per.synthx.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.SettableFuture
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.Song
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A song tap while the launch restore waits for its session connection (held open here): the tap
 * waits for the restore. Over a saved hand-built queue it then raises Keep/Clear and the saved queue
 * stays; over a saved queue that is not customised it plays, keeping the saved shuffle and repeat.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackViewModelRestoreTapInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    override val createEngineInSetUp = false

    private val held = SettableFuture.create<MediaController>()
    private val heldListener = AtomicReference<MediaController.Listener>()

    private fun saveQueue(customised: Boolean) {
        val saved = songs(4, "tap-saved").mapIndexed { i, song -> QueueSlot("tap-slot-$i", song) }
        recorder.snapshot = QueueSnapshot(
            saved,
            4,
            QueueCursor("tap-slot-1", 2_000L, true, Player.REPEAT_MODE_ALL, queueCustomized = customised),
        )
        recorder.baseline = saved
    }

    /** Requests the restore, taps the first of [tapped] while it is held, then lets it connect. */
    private fun tapDuringTheRestore(viewModel: PlaybackViewModel, tapped: List<Song>) {
        onMain {
            viewModel.restoreSavedQueue()
            viewModel.requestPlayQueue(tapped, 0)
        }
        SystemClock.sleep(500)
        assertTrue("nothing installed while the session is held", onMain { engine.orderStore.isEmpty })
        assertNull("nothing is shown while the tap is held", onMain { viewModel.queuePrompt.value })
        assertEquals(emptyList<QueueWrite.Unified>(), recorder.writesOf<QueueWrite.Unified>())
        onMain { held.setFuture(realConnection(heldListener.get())) }
    }

    private fun newViewModel(): PlaybackViewModel {
        engine = newEngine { listener ->
            heldListener.set(listener)
            held
        }
        return onMain { PlaybackViewModel(PlaybackController(engine)) }
    }

    @Test fun aTapOverASavedHandBuiltQueueRaisesKeepClearAndTheSavedQueueStays() {
        saveQueue(customised = true)
        val viewModel = newViewModel()
        val tapped = songs(2, "tap-new")

        tapDuringTheRestore(viewModel, tapped)

        await("the prompt") { onMain { viewModel.queuePrompt.value } != null }
        settle()
        assertEquals(tapped.first(), onMain { viewModel.queuePrompt.value?.tappedSong })
        assertEquals(
            listOf("tap-slot-0", "tap-slot-1", "tap-slot-2", "tap-slot-3"),
            onMain { engine.orderStore.live.map { it.slotId } },
        )
        assertEquals(
            "the saved queue is never replaced",
            emptyList<QueueWrite.Unified>(),
            recorder.writesOf<QueueWrite.Unified>(),
        )
    }

    @Test fun aTapOverASavedQueueThatIsNotCustomisedPlaysWithTheSavedModes() {
        saveQueue(customised = false)
        val viewModel = newViewModel()
        val tapped = songs(3, "tap-new")

        tapDuringTheRestore(viewModel, tapped)

        await("the tapped list playing") {
            onMain { engine.orderStore.live.map { it.songId }.toSet() } == tapped.map { it.id }.toSet()
        }
        settle()
        assertNull(onMain { viewModel.queuePrompt.value })
        val write = recorder.writesOf<QueueWrite.Unified>().last()
        assertEquals(tapped.map { it.id }.toSet(), write.activeSlots.map { it.songId }.toSet())
        assertTrue("the saved shuffle survives the tap", write.shuffleEnabled)
        assertEquals("the saved repeat survives the tap", Player.REPEAT_MODE_ALL, write.repeatMode)
    }
}
