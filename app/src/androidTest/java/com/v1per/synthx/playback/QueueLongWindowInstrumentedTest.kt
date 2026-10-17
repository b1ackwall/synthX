package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Queues longer than the ~51 items the player holds at once: the loaded window refills as playback
 * moves on, the end-of-window safety net keeps playing when the player runs out of loaded items,
 * and turning shuffle on mid-queue keeps the playing song first and saves once.
 */
@RunWith(AndroidJUnit4::class)
class QueueLongWindowInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    private fun loadedIds(): List<String> =
        onMain { (0 until extraController.mediaItemCount).map { extraController.getMediaItemAt(it).mediaId } }

    @Test fun theLoadedWindowRefillsAsPlaybackMovesThroughALongQueue() {
        playQueueAndAwait(songs(120, "win"), 0)
        val steps = WINDOW_FORWARD - WINDOW_REFILL_HEADROOM + 5

        repeat(steps) { step ->
            val target = onMain { engine.queue.now()[step + 1].slotId }
            onMain { engine.next() }
            await("on item ${step + 1}") { onMain { extraController.currentMediaItem?.mediaId } == target }
        }
        settle()

        val loaded = loadedIds()
        val current = onMain { engine.currentSlot()!!.slotId }
        val ahead = loaded.size - 1 - loaded.indexOf(current)
        assertTrue("loaded ${loaded.size} items, at most $WINDOW_SIZE", loaded.size <= WINDOW_SIZE)
        assertTrue("only $ahead items loaded ahead of the current one", ahead >= WINDOW_REFILL_HEADROOM)
        assertEquals("loaded items follow the queue order", onMain { engine.queue.now().slotIds() }.filter { it in loaded }, loaded)
    }

    /** The player reaches the end of what it holds while the queue goes on: playback continues. */
    @Test fun runningOffTheEndOfTheLoadedItemsKeepsPlayingTheNextQueuedSong() {
        playQueueAndAwait(songs(60, "edge"), 0)
        await("playing") { onMain { extraController.isPlaying } }
        val next = onMain { engine.queue.now()[1].slotId }
        // Leave the player only the current item, as if the window had not been refilled.
        onMain { extraController.removeMediaItems(1, extraController.mediaItemCount) }
        await("only the current item loaded") { onMain { extraController.mediaItemCount } == 1 }

        onMain { extraController.seekTo(59_300L) }

        await("playing the next queued song") {
            onMain { extraController.currentMediaItem?.mediaId == next && extraController.isPlaying }
        }
        assertEquals("not ended", false, onMain { engine.isQueueEnded.now() })
    }

    @Test fun shuffleOnMidQueueKeepsThePlayingSongFirstAndSavesOnce() {
        val list = songs(10, "mid")
        playQueueAndAwait(list, 3)
        await("load written") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        settle()
        recorder.writes.clear()
        val playing = onMain { engine.currentSlot()!!.slotId }

        runBlocking { engine.setShuffleEnabled(true) }
        await("shuffle saved") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        settle()

        onMain {
            assertTrue(engine.shuffleEnabled.now())
            assertEquals(playing, engine.queue.now().first().slotId)
            assertEquals(0, engine.currentIndex.now())
            assertEquals(list.map { it.id }.toSet(), engine.queue.now().songIds().toSet())
        }
        assertEquals(playing, onMain { extraController.currentMediaItem?.mediaId })
        val structural = recorder.writes.filterNot { it is QueueWrite.Cursor }
        assertEquals(listOf("Unified"), structural.map { it.javaClass.simpleName })
        val save = recorder.writesOf<QueueWrite.Unified>().single()
        assertTrue(save.shuffleEnabled)
        assertEquals(list.map { it.id }, save.baselineSlots.map { it.songId })
        assertEquals(Player.REPEAT_MODE_OFF, save.repeatMode)
    }
}
