package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.SettableFuture
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.Song
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A launch scan that gives a saved song a new id while the restore waits for its session: the scan
 * repoints the saved rows and publishes a remap that the engine, with nothing installed yet, cannot
 * apply. The restore notices the library changed during its wait, reads the saved rows once more
 * and installs the new id in the same slot, at the saved position.
 */
@RunWith(AndroidJUnit4::class)
class QueueRestoreRemapRaceInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()
    private lateinit var seed: RealDbSongSeed

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    override val createEngineInSetUp = false

    @Before fun setUpSeed() {
        seed = RealDbSongSeed(context)
    }

    @After fun cleanUpSeed() {
        seed.cleanUp()
    }

    private fun saved(songs: List<Song>, current: Int) = QueueSnapshot(
        slots = songs.mapIndexed { i, song -> QueueSlot("race-slot-$i", song) },
        storedSlotCount = songs.size,
        cursor = QueueCursor("race-slot-$current", 5_000L, false, Player.REPEAT_MODE_OFF),
    )

    @Test fun aRemapDuringTheRestoreWaitInstallsTheNewIdInTheSavedSlot() {
        val x = song("race-x")
        val old = song("race-old")
        val y = song("race-y")
        val fresh = song("race-new", title = "Retagged")
        listOf(x, old, y, fresh).forEach { seed.seed(it) }
        recorder.snapshot = saved(listOf(x, old, y), current = 1)
        recorder.baseline = recorder.snapshot.slots

        val gate = SettableFuture.create<MediaController>()
        var captured: MediaController.Listener? = null
        engine = newEngine { listener ->
            captured = listener
            gate
        }
        val restore = thread(name = "gated-restore") { runBlocking { engine.restoreSavedQueue() } }
        Thread.sleep(1_000) // the first read, artwork and the hop to main, parked in awaitController()
        assertTrue("the restore is still waiting for its session", restore.isAlive)
        assertTrue("nothing installed while it waits", onMain { engine.songsBySlotId.isEmpty() })

        // The scan's commit: the saved rows now hold the new id in the same slots; then its remap.
        recorder.snapshot = saved(listOf(x, fresh, y), current = 1)
        recorder.baseline = recorder.snapshot.slots
        runBlocking { SongRemapEvents.notifyRemap(old.id, fresh) }
        settle()

        gate.setFuture(onMain { realConnection(checkNotNull(captured)) })
        restore.join(15_000)
        assertTrue("restore finished", !restore.isAlive)
        settle()

        assertEquals(listOf("race-x", "race-new", "race-y"), onMain { engine.orderStore.live.map { it.songId } })
        assertEquals(listOf("race-x", "race-new", "race-y"), onMain { engine.orderStore.baseline.map { it.songId } })
        assertEquals("race-slot-1", onMain { engine._currentSlotId.value })
        assertEquals("race-new", onMain { engine.songsBySlotId["race-slot-1"]?.id })
        val position = onMain { engine.controller!!.currentPosition }
        assertTrue("the saved position is kept: $position ms", kotlin.math.abs(position - 5_000L) < 300)
    }
}
