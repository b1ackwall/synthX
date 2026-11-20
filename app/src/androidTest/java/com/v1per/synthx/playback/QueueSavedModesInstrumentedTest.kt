package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A saved cursor whose queue cannot be restored (no queue rows, or every saved song missing) still
 * brings back its repeat mode and shuffle flag: on the engine's state and on the session, and in
 * the next fresh play's save. Play still forces shuffle off and Shuffle forces it on.
 */
@RunWith(AndroidJUnit4::class)
class QueueSavedModesInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    private fun serveModesWithoutAQueue(storedSlotCount: Int = 0) {
        recorder.snapshot = QueueSnapshot(
            emptyList(),
            storedSlotCount,
            QueueCursor("gone-slot", 4_000L, shuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ALL),
        )
    }

    private fun restoreAndAwaitTheModes() {
        runBlocking { engine.restoreSavedQueue() }
        await("saved repeat on the session") { onMain { extraController.repeatMode == Player.REPEAT_MODE_ALL } }
        settle()
    }

    private fun freshPlayWrite(shuffle: Boolean?): QueueWrite.Unified {
        runBlocking { engine.playQueue(songs(4, "modes"), 0, shuffle) }
        await("fresh play written") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        settle()
        return recorder.writesOf<QueueWrite.Unified>().single()
    }

    @Test fun aCursorWithNoQueueRowsBringsBackItsModes() {
        serveModesWithoutAQueue()

        restoreAndAwaitTheModes()

        assertEquals(RepeatMode.All, onMain { engine.repeatMode.now() })
        assertTrue(onMain { engine.shuffleEnabled.now() })
        assertTrue("there is still no queue", onMain { engine.queue.now() }.isEmpty())
        assertEquals(0, onMain { extraController.mediaItemCount })
    }

    @Test fun aCursorWhoseSongsAreAllMissingBringsBackItsModes() {
        serveModesWithoutAQueue(storedSlotCount = 3)

        restoreAndAwaitTheModes()

        assertEquals(RepeatMode.All, onMain { engine.repeatMode.now() })
        assertTrue(onMain { engine.shuffleEnabled.now() })
        assertTrue(onMain { engine.queue.now() }.isEmpty())
    }

    @Test fun aSongTapAfterwardsSavesTheRestoredModesAndKeepsShuffleOn() {
        serveModesWithoutAQueue()
        restoreAndAwaitTheModes()

        val write = freshPlayWrite(shuffle = null)

        assertTrue("a song tap keeps the restored shuffle flag", write.shuffleEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, write.repeatMode)
        assertEquals(4, write.activeSlots.size)
    }

    @Test fun thePlayButtonAfterwardsStillTurnsShuffleOff() {
        serveModesWithoutAQueue()
        restoreAndAwaitTheModes()

        val write = freshPlayWrite(shuffle = false)

        assertFalse(write.shuffleEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, write.repeatMode)
    }

    @Test fun theShuffleButtonAfterwardsStillTurnsShuffleOn() {
        serveModesWithoutAQueue()
        restoreAndAwaitTheModes()

        val write = freshPlayWrite(shuffle = true)

        assertTrue(write.shuffleEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, write.repeatMode)
    }

    @Test fun aRunningSessionKeepsItsOwnModes() {
        playQueueAndAwait(songs(3, "running"), 0)
        onMain { engine.setRepeatMode(RepeatMode.One) }
        await("repeat one") { onMain { extraController.repeatMode == Player.REPEAT_MODE_ONE } }
        serveModesWithoutAQueue()
        recorder.adoptionRead = QueueSnapshot(emptyList(), 0, null) to emptyList()

        val second = newEngine()
        await("second engine connected") { onMain { second.controller != null } }
        runBlocking { second.restoreSavedQueue() }
        settle()

        assertEquals(Player.REPEAT_MODE_ONE, onMain { extraController.repeatMode })
        assertEquals(RepeatMode.One, onMain { second.repeatMode.now() })
    }
}
