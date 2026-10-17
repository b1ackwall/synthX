package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Up Next edits on the real engine and service: deleting a song from the library, moving,
 * Play Next, Add to Queue, tapping and removing a row. The baseline (the unshuffled order) is not
 * public, so it is read from the saves the engine makes, which is also what a restart would load.
 */
@RunWith(AndroidJUnit4::class)
class QueueEditInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    private fun play(list: List<Song>, startIndex: Int, shuffle: Boolean? = false) {
        runBlocking { engine.playQueue(list, startIndex, shuffle) }
        await("${list[startIndex].id} loaded in the session") {
            onMain { engine.currentSlot()?.let { it.song.id == list[startIndex].id && extraController.currentMediaItem?.mediaId == it.slotId } == true }
        }
        await("load written") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        settle()
        recorder.writes.clear()
    }

    private fun queueSongIds() = onMain { engine.queue.now().songIds() }

    private fun upcomingSlotIds() = onMain { engine.upcomingQueue.now().slotIds() }

    @Test fun deletingASongWithSeveralCopiesRemovesThemAllInOneBaselineSave() {
        play(songsWithIds("del-A", "del-B", "del-X", "del-B", "del-B"), 0)

        runBlocking { SongDeletionEvents.notifyDeleted("del-B") }
        await("every copy gone") { queueSongIds() == listOf("del-A", "del-X") }
        settle()

        val baselineSaves = recorder.writesOf<QueueWrite.Baseline>()
        assertEquals(recorder.writes.toString(), 1, baselineSaves.size)
        assertEquals(listOf("del-A", "del-X"), baselineSaves.single().slots.map { it.songId })
        assertFalse("a library deletion is not a user customisation", onMain { engine.isQueueCustomized.now() })
    }

    @Test fun deletingThePlayingSongSkipsPastItsOtherCopiesToTheNextDifferentSong() {
        play(songsWithIds("skip-A", "skip-B", "skip-B", "skip-X", "skip-C"), 1)

        runBlocking { SongDeletionEvents.notifyDeleted("skip-B") }
        await("B gone") { "skip-B" !in queueSongIds() }

        assertEquals(listOf("skip-A", "skip-X", "skip-C"), queueSongIds())
        assertEquals("skip-X", onMain { engine.currentSongId.now() })
        val xSlot = onMain { engine.currentSlot()!!.slotId }
        await("session on X") { onMain { extraController.currentMediaItem?.mediaId } == xSlot }
        // The queue save made for the deletion already names X as the resume point.
        await("queue saved") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        assertEquals(xSlot, recorder.writesOf<QueueWrite.QueueState>().first().currentSlotId)
    }

    /**
     * The skip target comes from the full order, not from the player: here every loaded item is a
     * copy of the playing B, so the next different song (X) is outside the loaded window and
     * nothing loaded is left to fall back on.
     */
    @Test fun deletingThePlayingSongWhoseCopiesFillTheLoadedWindowStillReachesTheNextDifferentSong() {
        val ids = List(WINDOW_FORWARD + 3) { "far-B" } + "far-X" + List(9) { "far-C$it" }
        play(songsWithIds(*ids.toTypedArray()), 0)
        assertTrue("precondition: X is not loaded", onMain { extraController.mediaItemCount } < ids.size)

        runBlocking { SongDeletionEvents.notifyDeleted("far-B") }
        await("B gone") { "far-B" !in queueSongIds() }

        assertEquals("far-X", onMain { engine.currentSongId.now() })
        val xSlot = onMain { engine.currentSlot()!!.slotId }
        await("session on X") { onMain { extraController.currentMediaItem?.mediaId } == xSlot }
    }

    @Test fun movingARowWhileShuffledChangesOnlyTheLiveOrder() {
        val list = songs(5, "mvs")
        play(list, 0, shuffle = true)
        val moved = upcomingSlotIds()[2]

        onMain { engine.moveQueueItem(moved, 0) }
        await("moved") { upcomingSlotIds().first() == moved }
        settle()

        assertTrue(recorder.writes.toString(), recorder.writesOf<QueueWrite.Baseline>().isEmpty())
        assertEquals(onMain { engine.queue.now().slotIds() }, recorder.writesOf<QueueWrite.QueueState>().last().slots.map { it.slotId })
        assertTrue(onMain { engine.isQueueCustomized.now() })

        // Shuffle off rebuilds from the untouched baseline: list order after the current song.
        runBlocking { engine.setShuffleEnabled(false) }
        assertEquals(list.map { it.id }, queueSongIds())
    }

    @Test fun movingARowUnshuffledMirrorsTheMoveIntoTheBaseline() {
        play(songs(5, "mvu"), 0)
        val moved = upcomingSlotIds()[2]

        onMain { engine.moveQueueItem(moved, 0) }
        await("moved") { upcomingSlotIds().first() == moved }
        await("baseline saved") { recorder.writesOf<QueueWrite.Baseline>().isNotEmpty() }

        assertEquals(listOf("mvu-0", "mvu-3", "mvu-1", "mvu-2", "mvu-4"), queueSongIds())
        assertEquals(queueSongIds(), recorder.writesOf<QueueWrite.Baseline>().last().slots.map { it.songId })
        assertEquals(moved, onMain { extraController.getMediaItemAt(1).mediaId })
    }

    @Test fun playNextUnshuffledInsertsAfterTheCurrentSongInLiveBaselineAndPlayer() {
        play(songs(4, "pn"), 1)

        assertTrue(onMain { engine.playNext(song("pn-new")) })
        await("baseline saved") { recorder.writesOf<QueueWrite.Baseline>().isNotEmpty() }
        settle()

        val expected = listOf("pn-0", "pn-1", "pn-new", "pn-2", "pn-3")
        assertEquals(expected, queueSongIds())
        assertEquals(expected, recorder.writesOf<QueueWrite.Baseline>().single().slots.map { it.songId })
        assertEquals(expected, recorder.writesOf<QueueWrite.QueueState>().single().slots.map { it.songId })
        assertTrue(onMain { engine.isQueueCustomized.now() })
        val newSlot = onMain { engine.queue.now()[2].slotId }
        assertEquals(newSlot, onMain { extraController.getMediaItemAt(2).mediaId })
    }

    @Test fun addToQueueWhileShuffledAppendsToTheLiveOrderOnly() {
        play(songs(4, "aq"), 0, shuffle = true)

        assertTrue(onMain { engine.addToQueue(song("aq-new")) })
        await("queue saved") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        settle()

        assertEquals("aq-new", queueSongIds().last())
        assertTrue(recorder.writes.toString(), recorder.writesOf<QueueWrite.Baseline>().isEmpty())
        assertEquals("aq-new", recorder.writesOf<QueueWrite.QueueState>().single().slots.last().songId)
        assertTrue(onMain { engine.isQueueCustomized.now() })
    }

    @Test fun tappingAnEarlierRowIsIgnoredAndALaterRowPlaysThatExactSlot() {
        play(songs(5, "tap"), 2)
        val (earlier, current, later) = onMain { engine.queue.now().let { Triple(it[0].slotId, it[2].slotId, it[4].slotId) } }

        onMain { engine.seekToQueueItem(earlier) }
        settle()
        assertEquals(current, onMain { engine.currentSlot()?.slotId })
        assertEquals(current, onMain { extraController.currentMediaItem?.mediaId })

        onMain { engine.seekToQueueItem(later) }
        await("later row playing") { onMain { extraController.currentMediaItem?.mediaId } == later }
        assertEquals(later, onMain { engine.currentSlot()?.slotId })
    }

    @Test fun removingARowRemovesThatSlotOnlyAndThePlayingRowCannotBeRemoved() {
        play(songs(5, "rm"), 1)
        val (current, removed) = onMain { engine.queue.now().let { it[1].slotId to it[3].slotId } }

        onMain { engine.removeQueueItem(removed) }
        await("row gone") { queueSongIds() == listOf("rm-0", "rm-1", "rm-2", "rm-4") }
        await("row gone from the player") { onMain { (0 until extraController.mediaItemCount).none { extraController.getMediaItemAt(it).mediaId == removed } } }
        settle()
        recorder.writes.clear()

        onMain { engine.removeQueueItem(current) }
        settle()
        assertEquals(listOf("rm-0", "rm-1", "rm-2", "rm-4"), queueSongIds())
        assertEquals(current, onMain { extraController.currentMediaItem?.mediaId })
        assertTrue(recorder.writes.toString(), recorder.writes.isEmpty())
    }
}
