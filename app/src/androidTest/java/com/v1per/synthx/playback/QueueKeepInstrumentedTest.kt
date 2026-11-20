package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Keep answer on the real engine and service: the tapped song plays right after the current
 * slot (a new copy unless it is already exactly next), the songs in between stay, the earlier copy
 * stays in history, a shuffled queue takes it as a Play Next extra, and a paused player starts.
 */
@RunWith(AndroidJUnit4::class)
class QueueKeepInstrumentedTest : PlaybackServiceTestBase() {
    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    private fun play(list: List<Song>, startIndex: Int, shuffle: Boolean? = null) {
        runBlocking { engine.playQueue(list, startIndex, shuffle) }
        await("${list[startIndex].id} loaded in the session") {
            onMain {
                engine.currentSlot()?.let {
                    it.song.id == list[startIndex].id && extraController.currentMediaItem?.mediaId == it.slotId
                } == true
            }
        }
        settle()
    }

    /** A view model over the engine, settled until it sees the engine's current queue. */
    private fun viewModel(): PlaybackViewModel {
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }
        await("the view model sees the queue") {
            onMain { viewModel.queue.value.size == engine.queue.now().size && viewModel.currentIndex.value >= 0 }
        }
        settle()
        return viewModel
    }

    private fun keep(viewModel: PlaybackViewModel, song: Song): Boolean? =
        onMain { viewModel.keepQueueAndPlayNext(QueuePromptEvent(song, listOf(song), 0)) }

    private fun queueSongIds() = onMain { engine.queue.now().songIds() }

    private fun slotIds() = onMain { engine.queue.now().slotIds() }

    private fun awaitCurrentIndex(index: Int) = await("current index $index") {
        onMain {
            engine.currentIndex.now() == index &&
                extraController.currentMediaItem?.mediaId == engine.currentSlot()?.slotId
        }
    }

    private fun awaitPlaying() = await("playing") { onMain { extraController.isPlaying } }

    @Test fun aKeepOnAPausedHandEditedQueueStartsPlayback() {
        play(songs(5, "keep"), 0)
        onMain { engine.playNext(song("keep-edit")) }
        onMain { engine.pause() }
        await("paused") { onMain { !extraController.isPlaying } }
        val viewModel = viewModel()

        assertEquals(true, keep(viewModel, song("keep-x")))

        awaitCurrentIndex(1)
        awaitPlaying()
        assertEquals(
            listOf("keep-0", "keep-x", "keep-edit", "keep-1", "keep-2", "keep-3", "keep-4"),
            queueSongIds(),
        )
    }

    @Test fun aKeepOfASongDeepInUpNextPlaysACopyAndSkipsNothing() {
        val list = songs(5, "deep")
        play(list, 0)
        val before = slotIds()
        val viewModel = viewModel()

        assertEquals(true, keep(viewModel, list[3]))

        awaitCurrentIndex(1)
        awaitPlaying()
        assertEquals(listOf("deep-0", "deep-3", "deep-1", "deep-2", "deep-3", "deep-4"), queueSongIds())
        assertEquals("the later copy keeps its own slot", before[3], slotIds()[4])
        assertNotEquals(before[3], onMain { engine.currentSlot()?.slotId })
    }

    @Test fun aKeepOfTheCurrentSongRestartsOnACopyAndHistoryKeepsTheEarlierOne() {
        val list = songs(4, "cur")
        play(list, 0)
        val before = slotIds()
        val viewModel = viewModel()

        assertEquals(true, keep(viewModel, list[0]))

        awaitCurrentIndex(1)
        awaitPlaying()
        assertEquals(listOf("cur-0", "cur-0", "cur-1", "cur-2", "cur-3"), queueSongIds())
        assertEquals(before[0], slotIds()[0])
        assertNotEquals(before[0], onMain { engine.currentSlot()?.slotId })
    }

    @Test fun aKeepOfTheSongExactlyNextPlaysThatSlotWithoutACopy() {
        val list = songs(4, "next")
        play(list, 0)
        val before = slotIds()
        val viewModel = viewModel()

        assertEquals(true, keep(viewModel, list[1]))

        awaitCurrentIndex(1)
        awaitPlaying()
        assertEquals(before, slotIds())
        assertEquals(before[1], onMain { engine.currentSlot()?.slotId })
    }

    @Test fun aKeepWhileShuffledPlaysAPlayNextExtra() {
        play(songs(6, "shuf"), 0, shuffle = true)
        val viewModel = viewModel()
        val x = song("shuf-x")

        assertEquals(true, keep(viewModel, x))

        awaitCurrentIndex(1)
        awaitPlaying()
        val current = onMain { engine.currentSlot()!! }
        assertEquals("shuf-x", current.song.id)
        assertEquals(QueueExtraKind.PlayNext, onMain { engine.orderStore.extras[current.slotId]?.kind })
        assertEquals(7, queueSongIds().size)
        assertEquals(queueSongIds().toSet(), (songs(6, "shuf").map { it.id } + "shuf-x").toSet())
    }
}
