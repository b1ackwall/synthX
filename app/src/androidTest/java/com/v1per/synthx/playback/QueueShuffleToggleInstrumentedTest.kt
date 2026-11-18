package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shuffle ON and OFF on the real engine and service: nothing is lost, history stays, Play Next
 * stays next, the playing item is never replaced, the session extras and the reorder flag are
 * saved and restored. Writes go to a recording repository, which is what a restart would read.
 */
@RunWith(AndroidJUnit4::class)
class QueueShuffleToggleInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    private fun play(list: List<Song>, startIndex: Int, shuffle: Boolean?) {
        runBlocking { engine.playQueue(list, startIndex, shuffle) }
        await("${list[startIndex].id} loaded in the session") {
            onMain {
                engine.currentSlot()?.let {
                    it.song.id == list[startIndex].id && extraController.currentMediaItem?.mediaId == it.slotId
                } == true
            }
        }
        await("load written") { recorder.writesOf<QueueWrite.Unified>().isNotEmpty() }
        settle()
        recorder.writes.clear()
    }

    private fun toggleShuffle(enabled: Boolean) {
        runBlocking { engine.setShuffleEnabled(enabled) }
        await("shuffle $enabled") { onMain { engine.shuffleEnabled.now() == enabled } }
        await("toggle written") { recorder.writesOf<QueueWrite.Unified>().any { it.shuffleEnabled == enabled } }
        settle()
    }

    private fun queueSongIds() = onMain { engine.queue.now().songIds() }

    private fun upcomingSongIds() = onMain { engine.upcomingQueue.now().songIds() }

    private fun playingMediaId() = onMain { extraController.currentMediaItem?.mediaId }

    private fun awaitCurrentSong(id: String) = await("current song $id") {
        onMain {
            engine.currentSongId.now() == id &&
                extraController.currentMediaItem?.mediaId == engine.currentSlot()?.slotId
        }
    }

    private fun ids(prefix: String, range: IntProgression) = range.map { "$prefix-$it" }

    @Test fun keepThenShuffleOffKeepsEverySongOfAHandEditedQueue() {
        val list = songs(8, "keep")
        play(list, 0, shuffle = false)
        assertTrue(onMain { engine.playNext(song("keep-A")) })
        toggleShuffle(true)

        // The Keep answer: Play Next the tapped song, then jump to it.
        assertTrue(onMain { engine.playNext(song("keep-X")) })
        onMain { engine.next() }
        awaitCurrentSong("keep-X")
        val playing = playingMediaId()

        toggleShuffle(false)

        assertEquals(listOf("keep-0", "keep-X", "keep-A") + ids("keep", 1..7), queueSongIds())
        assertEquals("keep-X", onMain { engine.currentSongId.now() })
        assertEquals("the playing item is untouched", playing, playingMediaId())
        val save = recorder.writesOf<QueueWrite.Unified>().last()
        assertEquals(save.activeSlots, save.baselineSlots)
        assertTrue(save.extras.isEmpty())
    }

    @Test fun playNextWhileShuffledIsSavedAsAnExtraAndStaysNextThroughShuffleOff() {
        play(songs(6, "pn"), 0, shuffle = true)

        assertTrue(onMain { engine.playNext(song("pn-X")) })
        assertTrue(onMain { engine.playNext(song("pn-Y")) })
        await("both queued") { upcomingSongIds().take(2) == listOf("pn-Y", "pn-X") }
        settle()
        val saved = recorder.writesOf<QueueWrite.QueueState>().last()
        val slotOf = saved.slots.associate { it.songId to it.slotId }
        val x = saved.extras.getValue(slotOf.getValue("pn-X"))
        val y = saved.extras.getValue(slotOf.getValue("pn-Y"))
        assertEquals(QueueExtraKind.PlayNext, x.kind)
        assertEquals(QueueExtraKind.PlayNext, y.kind)
        assertTrue(y.sequence > x.sequence)

        toggleShuffle(false)

        assertEquals(listOf("pn-Y", "pn-X") + ids("pn", 1..5), upcomingSongIds())
        val save = recorder.writesOf<QueueWrite.Unified>().last()
        assertTrue(save.extras.isEmpty())
        assertFalse(save.shuffleReordered)
    }

    @Test fun shuffleOnAtTheLastSongChangesNothingAndAPlayNextSongStaysNext() {
        val list = songs(4, "last")
        play(list, 3, shuffle = false)

        toggleShuffle(true)
        assertEquals(list.map { it.id }, queueSongIds())

        assertTrue(onMain { engine.playNext(song("last-X")) })
        await("queued") { upcomingSongIds() == listOf("last-X") }
        toggleShuffle(false)

        assertEquals(list.map { it.id } + "last-X", queueSongIds())
        assertEquals(listOf("last-X"), upcomingSongIds())
    }

    @Test fun theShuffleButtonThenShuffleOffKeepsTheSongsBeforeTheStartSong() {
        play(songs(12, "btn"), 6, shuffle = true)
        val playing = playingMediaId()

        toggleShuffle(false)

        assertEquals(ids("btn", 6..11) + ids("btn", 0..5), queueSongIds())
        assertEquals(0, onMain { engine.currentIndex.now() })
        assertEquals(playing, playingMediaId())
    }

    @Test fun aReorderWhileShuffledIsFlaggedSavedAndSurvivesATrackChangeAndAPause() {
        play(songs(8, "ro"), 0, shuffle = true)
        val moved = onMain { engine.upcomingQueue.now()[2].slotId }

        onMain { engine.moveQueueItem(moved, 0) }
        await("flag set") { onMain { engine.shuffleReorderedByUser.now() } }
        await("reorder written") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }
        assertEquals(true, recorder.writesOf<QueueWrite.QueueState>().last().shuffleReordered)

        val before = onMain { engine.currentSongId.now() }
        onMain { engine.next() }
        await("advanced") { onMain { engine.currentSongId.now() != before } }
        onMain { engine.pause() }
        await("paused write") { recorder.writesOf<QueueWrite.QueueState>().size >= 2 }
        settle()
        assertTrue(onMain { engine.shuffleReorderedByUser.now() })
        assertEquals(true, recorder.writesOf<QueueWrite.QueueState>().last().shuffleReordered)

        toggleShuffle(false)
        assertFalse(onMain { engine.shuffleReorderedByUser.now() })
        assertFalse(recorder.writesOf<QueueWrite.Unified>().last().shuffleReordered)
    }

    @Test fun e12TheMovedSongGoesBackToItsOriginalPlaceOnShuffleOff() {
        val list = songs(8, "e12")
        play(list, 0, shuffle = true)
        val moved = onMain { engine.upcomingQueue.now().first { it.song.id == "e12-5" }.slotId }

        onMain { engine.moveQueueItem(moved, 0) }
        await("moved to the top") { upcomingSongIds().first() == "e12-5" }
        toggleShuffle(false)

        assertEquals(list.map { it.id }, queueSongIds())
    }

    @Test fun aRestartWithExtrasRebuildsThemAndShuffleOffStillPlacesThem() {
        val album = songs(5, "rs").mapIndexed { i, song -> QueueSlot("rs-slot-$i", song) }
        val x = QueueSlot("rs-slot-X", song("rs-X"))
        val y = QueueSlot("rs-slot-Y", song("rs-Y"))
        val live = listOf(album[0], x, album[3], album[1], y, album[4], album[2])
        val extras = mapOf(
            x.slotId to QueueExtraTag(QueueExtraKind.PlayNext, 2),
            y.slotId to QueueExtraTag(QueueExtraKind.AddToQueue, 1),
        )
        recorder.snapshot = QueueSnapshot(
            live,
            live.size,
            QueueCursor(album[0].slotId, 0L, shuffleEnabled = true, repeatMode = 0, shuffleReordered = true),
            extras,
        )
        recorder.baseline = album

        runBlocking { engine.restoreSavedQueue() }
        await("restored") { onMain { extraController.currentMediaItem?.mediaId == album[0].slotId } }

        assertEquals(extras, onMain { engine.orderStore.extras })
        assertTrue(onMain { engine.shuffleReorderedByUser.now() })

        toggleShuffle(false)
        assertEquals(listOf("rs-0", "rs-X", "rs-1", "rs-2", "rs-3", "rs-4", "rs-Y"), queueSongIds())
    }

    @Test fun aRestartWhileAPlayNextExtraIsPlayingKeepsTheAlbumOrderForShuffleOff() {
        val album = songs(5, "rx").mapIndexed { i, song -> QueueSlot("rx-slot-$i", song) }
        val x = QueueSlot("rx-slot-X", song("rx-X"))
        val live = listOf(album[0], x, album[3], album[1], album[4], album[2])
        recorder.snapshot = QueueSnapshot(
            live,
            live.size,
            QueueCursor(x.slotId, 0L, shuffleEnabled = true, repeatMode = 0),
            mapOf(x.slotId to QueueExtraTag(QueueExtraKind.PlayNext, 0)),
        )
        recorder.baseline = album

        runBlocking { engine.restoreSavedQueue() }
        await("restored on X") { onMain { extraController.currentMediaItem?.mediaId == x.slotId } }
        toggleShuffle(false)

        assertEquals(listOf("rx-0", "rx-X", "rx-1", "rx-2", "rx-3", "rx-4"), queueSongIds())
    }

    @Test fun theRestoreRewriteForDroppedSongsKeepsTheExtrasAndTheFlag() {
        val album = songs(3, "dr").mapIndexed { i, song -> QueueSlot("dr-slot-$i", song) }
        val y = QueueSlot("dr-slot-Y", song("dr-Y"))
        val live = listOf(album[0], album[2], y, album[1])
        val extras = mapOf(y.slotId to QueueExtraTag(QueueExtraKind.AddToQueue, 3))
        recorder.snapshot = QueueSnapshot(
            live,
            storedSlotCount = live.size + 1,
            cursor = QueueCursor(album[0].slotId, 0L, shuffleEnabled = true, repeatMode = 0, shuffleReordered = true),
            extras = extras,
        )
        recorder.baseline = album

        runBlocking { engine.restoreSavedQueue() }
        await("rewrite for the dropped song") { recorder.writesOf<QueueWrite.QueueState>().isNotEmpty() }

        val rewrite = recorder.writesOf<QueueWrite.QueueState>().single()
        assertEquals(live.map { it.slotId }, rewrite.slots.map { it.slotId })
        assertEquals(extras, rewrite.extras)
        assertEquals(true, rewrite.shuffleReordered)
    }

    @Test fun aRestoredQueueSavedWithoutTagsTreatsItsExtrasAsAddToQueue() {
        val album = songs(3, "lg").mapIndexed { i, song -> QueueSlot("lg-slot-$i", song) }
        val x = QueueSlot("lg-slot-X", song("lg-X"))
        val live = listOf(album[0], x, album[2], album[1])
        recorder.snapshot = QueueSnapshot(live, live.size, QueueCursor(album[0].slotId, 0L, true, 0))
        recorder.baseline = album

        runBlocking { engine.restoreSavedQueue() }
        await("restored") { onMain { extraController.currentMediaItem?.mediaId == album[0].slotId } }
        toggleShuffle(false)

        assertEquals(listOf("lg-0", "lg-1", "lg-2", "lg-X"), queueSongIds())
    }

    @Test fun repeatAllShufflesTheHistoryInAndShuffleOffWrapsAfterThePlayingSong() {
        startQueue(count = 8, startIndex = 3, repeat = RepeatMode.All)
        recorder.writes.clear()
        val playing = playingMediaId()

        toggleShuffle(true)
        onMain {
            assertEquals("song-3", engine.queue.now().first().song.id)
            assertEquals(0, engine.currentIndex.now())
        }
        toggleShuffle(false)

        assertEquals(ids("song", 3..7) + ids("song", 0..2), queueSongIds())
        assertEquals(playing, playingMediaId())
    }

    @Test fun repeatModeChangesNeverReorderAShuffledQueue() {
        play(songs(8, "rp"), 2, shuffle = false)
        toggleShuffle(true)
        val order = queueSongIds()

        for (mode in listOf(RepeatMode.All, RepeatMode.Off, RepeatMode.One, RepeatMode.All, RepeatMode.Off)) {
            onMain { engine.setRepeatMode(mode) }
            await("repeat $mode") { onMain { engine.repeatMode.now() == mode } }
            settle()
            assertEquals("order after repeat $mode", order, queueSongIds())
        }
    }

    @Test fun togglesOnALongQueueNeverReplaceThePlayingItemOrRestartIt() {
        val list = songs(120, "long")
        play(list, 70, shuffle = false)
        await("playing") { onMain { extraController.isPlaying } }
        val playing = playingMediaId()
        Thread.sleep(1_200)
        val positionBefore = onMain { extraController.currentPosition }

        toggleShuffle(true)
        assertEquals(playing, playingMediaId())
        assertTrue("still playing", onMain { extraController.isPlaying })
        assertTrue("not restarted", onMain { extraController.currentPosition } >= positionBefore)
        assertTrue("only a window is loaded", onMain { extraController.mediaItemCount } <= WINDOW_SIZE)
        assertEquals(ids("long", 0..70), queueSongIds().take(71))

        toggleShuffle(false)
        assertEquals(playing, playingMediaId())
        assertTrue(onMain { extraController.isPlaying })
        assertEquals(list.map { it.id }, queueSongIds())
    }
}
