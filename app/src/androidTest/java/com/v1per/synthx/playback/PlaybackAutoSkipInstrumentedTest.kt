package com.v1per.synthx.playback

import android.net.Uri
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Songs whose file cannot be read are skipped automatically: a missing, corrupt or unreadable file
 * moves playback on to the next slot in queue order, playing it only when the user was playing.
 * One message per burst of consecutive failures; a burst stops at five failures, or after one try
 * of every slot under Repeat All and Repeat One, so a queue of bad files never spins.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackAutoSkipInstrumentedTest : PlaybackServiceTestBase() {

    private val errors = CopyOnWriteArrayList<PlaybackError>()
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val createdFiles = mutableListOf<File>()

    @After fun cleanUp() {
        collectorScope.cancel()
        // Deleting needs write access to the folder only, so an unreadable file goes too.
        createdFiles.forEach { it.delete() }
    }

    private fun collectErrors() {
        // Undispatched, so the collector is subscribed before anything can fail (errors keep no replay).
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.errors.collect { errors += it } }
    }

    private fun songAt(id: String, file: File): Song =
        song(id, title = "Bad $id").copy(filePath = Uri.fromFile(file).toString())

    private fun missingSong(id: String): Song = songAt(id, File(context.cacheDir, "autoskip-missing-$id.wav"))

    private fun corruptSong(id: String): Song {
        val file = File(context.cacheDir, "autoskip-corrupt-$id.wav")
        // Zeros: no container sniffs them, unlike random bytes, which can look like an MP3 frame header.
        file.writeBytes(ByteArray(64 * 1024))
        createdFiles += file
        return songAt(id, file)
    }

    private fun unreadableSong(id: String): Song {
        val file = File(context.cacheDir, "autoskip-unreadable-$id.wav")
        wav.copyTo(file, overwrite = true)
        assertTrue("precondition: the copy can be made unreadable", file.setReadable(false, false))
        createdFiles += file
        return songAt(id, file)
    }

    private fun play(list: List<Song>, startIndex: Int) {
        collectErrors()
        runBlocking { engine.playQueue(list, startIndex) }
    }

    private fun liveSlot(index: Int): String = onMain { engine.orderStore.live[index].slotId }

    private fun currentIndex(): Int = onMain { engine.orderStore.indexOfSlot(engine._currentSlotId.value) }

    private fun awaitPlayingAt(index: Int) {
        val slot = liveSlot(index)
        await("playing slot $index") {
            onMain { extraController.currentMediaItem?.mediaId == slot && extraController.isPlaying }
        }
    }

    /** Waits for a failure burst to come to rest on [index]: the player failed there and nothing moves. */
    private fun awaitStoppedAt(index: Int) {
        val slot = liveSlot(index)
        await("stopped with an error on slot $index") {
            onMain {
                extraController.currentMediaItem?.mediaId == slot && extraController.playerError != null &&
                    extraController.playbackState == Player.STATE_IDLE
            }
        }
        settle()
        assertEquals("still on slot $index once everything settled", index, currentIndex())
        assertEquals("the player stays stopped", Player.STATE_IDLE, onMain { extraController.playbackState })
    }

    private fun setRepeat(mode: RepeatMode) {
        await("connected") { onMain { engine.connection.isConnected } }
        onMain { engine.setRepeatMode(mode) }
        await("repeat $mode") { onMain { engine._repeatMode.value == mode } }
    }

    /** Lets the playing (good) song run to its end, so playback reaches the next slot on its own. */
    private fun playToTheEnd(index: Int) {
        val slot = liveSlot(index)
        await("slot $index ready", timeoutMs = 30_000L) {
            onMain {
                val ready = extraController.currentMediaItem?.mediaId == slot &&
                    extraController.playbackState == Player.STATE_READY && extraController.duration > 0
                if (ready && extraController.currentPosition < 58_000L) extraController.seekTo(59_300L)
                extraController.currentMediaItem?.mediaId != slot
            }
        }
    }

    private fun failed(vararg titles: String) = titles.map { PlaybackError.PlaybackFailed(it) }

    @Test fun aCorruptFirstSongIsSkippedAndTheNextSongPlays() {
        play(listOf(corruptSong("c"), song("ok-1"), song("ok-2")), 0)
        awaitPlayingAt(1)
        settle()
        assertEquals(1, currentIndex())
        assertEquals("the bad song keeps its slot", 3, onMain { engine.orderStore.size })
        assertEquals(failed("Bad c"), errors.toList())
    }

    @Test fun anUnreadableFirstSongIsSkippedAndTheNextSongPlays() {
        play(listOf(unreadableSong("u"), song("ok-1"), song("ok-2")), 0)
        awaitPlayingAt(1)
        settle()
        assertEquals(1, currentIndex())
        assertEquals(failed("Bad u"), errors.toList())
    }

    @Test fun aPausedUserIsMovedPastABadSongAndStaysPaused() {
        play(listOf(song("ok-0"), missingSong("m"), song("ok-2")), 0)
        awaitPlayingAt(0)
        onMain { engine.pause() }
        await("paused") { onMain { !extraController.isPlaying } }

        onMain { engine.next() }

        val target = liveSlot(2)
        await("moved past the bad song") {
            onMain {
                extraController.currentMediaItem?.mediaId == target &&
                    extraController.playbackState == Player.STATE_READY
            }
        }
        settle()
        assertEquals(2, currentIndex())
        assertFalse("a paused user stays paused", onMain { extraController.isPlaying })
        assertFalse("play-when-ready stays off", onMain { extraController.playWhenReady })
        assertEquals(failed("Bad m"), errors.toList())
    }

    @Test fun aBadLastSongUnderRepeatOffStaysThereWithOneMessage() {
        play(listOf(song("ok-0"), missingSong("last")), 1)
        awaitStoppedAt(1)
        assertEquals(failed("Bad last"), errors.toList())
    }

    @Test fun repeatOneSkipsForwardFromTheLastSlotToTheFirst() {
        play(listOf(song("one-0"), missingSong("one-bad")), 0)
        awaitPlayingAt(0)
        setRepeat(RepeatMode.One)

        onMain { engine.next() }

        // The bad last slot fails, and Repeat One's next slot wraps to the first, as Next does.
        await("failed on the last slot") { errors.isNotEmpty() }
        awaitPlayingAt(0)
        settle()
        assertEquals(0, currentIndex())
        assertEquals(failed("Bad one-bad"), errors.toList())
    }

    @Test fun repeatAllWithEveryBadSongTriesEachSlotOnceAndStops() {
        setRepeat(RepeatMode.All)
        // Starts on the middle slot, so the stop lands on the first slot only by wrapping.
        play(listOf(missingSong("a0"), missingSong("a1"), missingSong("a2")), 1)
        awaitStoppedAt(0)
        assertEquals(failed("Bad a1"), errors.toList())
    }

    @Test fun repeatAllStopsAtTheCapOfFiveFailures() {
        setRepeat(RepeatMode.All)
        play((0 until 7).map { missingSong("cap$it") }, 0)
        awaitStoppedAt(4)
        assertEquals(failed("Bad cap0"), errors.toList())
    }

    @Test fun aGoodSongPlayingBetweenTwoBurstsResetsTheCounter() {
        val list = listOf(missingSong("b0"), song("good-1")) + (2..6).map { missingSong("b$it") } + song("good-7")
        play(list, 0)
        awaitPlayingAt(1)

        playToTheEnd(1)

        // The second burst counts from one again, so it gets five tries (slots 2 to 6) and a message of its own.
        awaitStoppedAt(6)
        assertEquals(failed("Bad b0", "Bad b2"), errors.toList())
    }

    @Test fun aUserNextAfterABurstStoppedStartsANewBurst() {
        play((0 until 7).map { missingSong("n$it") }, 0)
        awaitStoppedAt(4)

        onMain { engine.next() }

        awaitStoppedAt(6)
        assertEquals(failed("Bad n0", "Bad n5"), errors.toList())
    }
}
