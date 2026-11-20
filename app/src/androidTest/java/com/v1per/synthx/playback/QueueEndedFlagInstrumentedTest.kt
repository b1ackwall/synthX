package com.v1per.synthx.playback

import android.util.Log
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue-ended flag after a natural end. media3 keeps the player in `STATE_ENDED` when Repeat All
 * (and then Shuffle ON) give the ended queue a next slot again, so the engine itself clears the flag
 * on those changes while a next slot exists, and sets it again when the next slot goes away while the
 * player is still ended. Play Next and Add to Queue then add to the queue instead of replacing it.
 */
@RunWith(AndroidJUnit4::class)
class QueueEndedFlagInstrumentedTest : PlaybackServiceTestBase() {

    @Test fun repeatAllAfterTheNaturalEndClearsTheEndedFlag() {
        endTheQueue("repeat")
        onMain { engine.setRepeatMode(RepeatMode.All) }
        await("repeat all applied") { onMain { engine._repeatMode.value == RepeatMode.All } }
        assertTrue("Repeat All gives the ended queue a next slot", onMain { engine._hasNext.value })
        settle()

        val state = onMain { extraController.playbackState }
        Log.i(TAG, "after Repeat All at the end: playbackState=$state")
        assertFalse("ended flag after Repeat All (playbackState=$state)", onMain { engine._isQueueEnded.value })
    }

    @Test fun repeatAllThenShuffleOnAfterTheNaturalEndClearsTheEndedFlag() {
        endTheQueue("shuffle")
        onMain { engine.setRepeatMode(RepeatMode.All) }
        await("repeat all applied") { onMain { engine._repeatMode.value == RepeatMode.All } }
        runBlocking { engine.setShuffleEnabled(true) }
        assertTrue("Up Next filled by shuffle ON", onMain { engine._upcomingQueue.value.isNotEmpty() })
        settle()

        val state = onMain { extraController.playbackState }
        Log.i(TAG, "after Repeat All and Shuffle ON at the end: playbackState=$state")
        assertFalse(
            "ended flag after Repeat All and Shuffle ON (playbackState=$state)",
            onMain { engine._isQueueEnded.value },
        )
    }

    @Test fun repeatOffAgainWhileStillEndedSetsTheEndedFlagAgain() {
        endTheQueue("relatch")
        onMain { engine.setRepeatMode(RepeatMode.All) }
        await("repeat all applied") { onMain { engine._repeatMode.value == RepeatMode.All } }
        settle()
        assertFalse("cleared while a next slot exists", onMain { engine._isQueueEnded.value })

        onMain { engine.setRepeatMode(RepeatMode.Off) }
        await("repeat off applied") { onMain { engine._repeatMode.value == RepeatMode.Off } }
        settle()

        assertEquals("the player never left the end", Player.STATE_ENDED, onMain { extraController.playbackState })
        assertTrue("a plain ended queue reads as ended again", onMain { engine._isQueueEnded.value })
    }

    /** Plays a 3-song queue from its last song to its natural end under Repeat Off. */
    private fun endTheQueue(prefix: String) {
        playQueueAndAwait(songs(3, prefix), startIndex = 2)
        // A seek issued before the item is prepared can be dropped, which leaves a minute of audio
        // to play out; seek only once the player is ready with a known duration, and again if needed.
        await("last song ready") {
            onMain { extraController.playbackState == Player.STATE_READY && extraController.duration > 0 }
        }
        await("playback reached the end of the queue", timeoutMs = 30_000L) {
            onMain {
                val state = extraController.playbackState
                if (state != Player.STATE_ENDED && extraController.currentPosition < 58_000L) {
                    extraController.seekTo(59_300L)
                }
                state == Player.STATE_ENDED
            }
        }
        await("queue-ended flag set") { onMain { engine._isQueueEnded.value } }
    }

    private companion object {
        const val TAG = "QueueEndedFlagTest"
    }
}
