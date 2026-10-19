package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Natural end-of-queue (last song finishes, Repeat Off, nothing next) against the real service
 * and a real [ExoPlayerEngine]. The counterpart of
 * [PlaybackCacheInvalidationInstrumentedTest.deletingTheFinalCurrentSongWithDuplicateOccurrencesEndsStoppedWithANullCursor]:
 * deletion must end with a null cursor, but a natural end must NOT — it leaves the last song
 * published so the player sheet stays composed (an ordinary paused player) instead of vanishing in
 * the frame the state clears.
 *
 * Writes go to a recording repository, so the resume cursor the engine persists at the end can be
 * asserted directly.
 */
@RunWith(AndroidJUnit4::class)
class NaturalQueueEndInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    /** Every cursor-carrying write the engine made, in order: slot id to position. */
    private val cursorWrites: List<Pair<String?, Long>>
        get() = recorder.writes.mapNotNull {
            when (it) {
                is QueueWrite.Cursor -> it.currentSlotId to it.positionMs
                is QueueWrite.QueueState -> it.currentSlotId to it.positionMs
                else -> null
            }
        }

    @Test fun naturalEndKeepsTheLastSongPublishedPausedAndResetsTheResumeCursor() {
        playQueueAndAwait(songs(2, "end"), startIndex = 1)
        assertEquals("precondition: repeat off", RepeatMode.Off, onMain { engine._repeatMode.value })
        val lastSlotId = onMain { engine.orderStore.live.last().slotId }
        val lastSongId = onMain { engine._currentSongId.value }
        assertEquals("precondition: on the last song", "end-1", lastSongId)

        // Skip to just short of the end of the 60s track so the natural end arrives promptly.
        onMain { extraController.seekTo(59_300L) }
        awaitPlaybackFinished()
        // "After all callbacks settle": the onIsPlayingChanged checkpoint and the session's own
        // state updates land asynchronously, and are what could null or re-position things.
        settle()

        assertEquals("current slot", lastSlotId, onMain { engine._currentSlotId.value })
        assertEquals("current song id", lastSongId, onMain { engine._currentSongId.value })
        assertNotNull("current song", onMain { engine._currentSong.value })
        assertEquals("cursor index", 1, onMain { engine._currentIndex.value })
        assertFalse("not playing", onMain { engine._isPlaying.value })
        assertEquals(
            "queue untouched",
            listOf("end-0", "end-1"),
            onMain {
                engine.orderStore.live.map { songsOf(it.slotId) }
            },
        )
        assertTrue("timeline still loaded", onMain { engine.controller!!.mediaItemCount } > 0)
        assertEquals("session parked at end of timeline", Player.STATE_ENDED, onMain { extraController.playbackState })
        assertTrue("queue-ended flag", onMain { engine._isQueueEnded.value })

        val finalWrite = cursorWrites.last { it.first == lastSlotId }
        assertEquals("resume cursor names the last slot", lastSlotId, finalWrite.first)
        assertEquals("resume cursor is the start of the track, not its end", 0L, finalWrite.second)
    }

    @Test fun playingAgainFromTheEndedStateRestartsTheLastSongAndClearsTheEndedFlag() {
        playQueueAndAwait(songs(2, "again"), startIndex = 1)
        onMain { extraController.seekTo(59_300L) }
        awaitPlaybackFinished()
        assertTrue("ended flag set", onMain { engine._isQueueEnded.value })

        onMain { engine.resume() }
        await("playing again") { onMain { engine._isPlaying.value } }
        await("ended flag cleared") { onMain { !engine._isQueueEnded.value } }
        assertEquals("same song", "again-1", onMain { engine._currentSongId.value })
    }

    /**
     * Waits on the session's own state (ended, or idle after a stop) rather than the engine's
     * ended flag, so a regression that stops or clears on natural end fails the behavioural
     * assertions that follow instead of timing out here.
     */
    private fun awaitPlaybackFinished() = await("playback reached the end of the queue") {
        onMain { extraController.playbackState.let { it == Player.STATE_ENDED || it == Player.STATE_IDLE } }
    }

    private fun songsOf(slotId: String): String? = engine.songsBySlotId[slotId]?.id
}
