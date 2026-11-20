package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A shuffle command from another controller (a car, a headset, an assistant, any media3 controller)
 * reaches the app's own shuffle, never ExoPlayer's: ON shuffles the queue by the app's rules and the
 * app's order drives playback; OFF restores the original order, except that it is refused while the
 * queue was reordered while shuffled. Controllers see the app's flag. With no engine, the command is
 * not offered and does nothing.
 */
@RunWith(AndroidJUnit4::class)
class ExternalShuffleInstrumentedTest : PlaybackServiceTestBase() {

    private fun liveIds(): List<String> = onMain { engine.orderStore.live.map { it.slotId } }

    private fun externalShuffle(enabled: Boolean) = onMain { extraController.shuffleModeEnabled = enabled }

    private fun awaitAppShuffle(enabled: Boolean) =
        await("app shuffle $enabled") { onMain { engine._shuffleEnabled.value == enabled } }

    @Test fun anExternalShuffleOnShufflesTheQueueAndTheAppOrderDrivesPlayback() {
        startQueue(24, 0, RepeatMode.Off)
        val before = liveIds()

        externalShuffle(true)
        awaitAppShuffle(true)
        settle()

        val after = liveIds()
        assertEquals("the current slot keeps its place", before[0], after[0])
        assertEquals("same slots", before.toSet(), after.toSet())
        assertNotEquals("the rest is shuffled", before, after)
        assertTrue("the controller sees shuffle on", onMain { extraController.shuffleModeEnabled })

        // The next song is the app's next slot, not one ExoPlayer picked from its own random order.
        onMain { engine.resume() }
        await("the first song ends", timeoutMs = 30_000L) {
            onMain {
                val first = extraController.currentMediaItem?.mediaId == after[0]
                if (first && extraController.playbackState == Player.STATE_READY &&
                    extraController.currentPosition < 58_000L
                ) {
                    extraController.seekTo(59_300L)
                }
                !first
            }
        }
        assertEquals("playback follows the app's order", after[1], onMain { extraController.currentMediaItem?.mediaId })
        assertEquals(1, onMain { engine._currentIndex.value })
    }

    @Test fun anExternalShuffleOffRestoresTheOriginalOrder() {
        startQueue(5, 0, RepeatMode.Off)
        val before = liveIds()
        externalShuffle(true)
        awaitAppShuffle(true)
        settle()

        externalShuffle(false)
        awaitAppShuffle(false)
        settle()

        assertEquals(before, liveIds())
        assertFalse("the controller sees shuffle off", onMain { extraController.shuffleModeEnabled })
    }

    @Test fun anExternalShuffleOffAfterAReorderIsRefusedAndTheControllerSeesShuffleOnAgain() {
        startQueue(5, 0, RepeatMode.Off)
        externalShuffle(true)
        awaitAppShuffle(true)
        settle()
        val upcoming = onMain { engine._upcomingQueue.value.map { it.slotId } }
        onMain { engine.moveQueueItem(upcoming[3], 0) }
        assertTrue("precondition: reordered while shuffled", onMain { engine._shuffleReorderedByUser.value })
        val reordered = liveIds()

        externalShuffle(false)
        settle()

        assertTrue("shuffle stays on", onMain { engine._shuffleEnabled.value })
        assertEquals("nothing is discarded", reordered, liveIds())
        assertTrue("the controller is told shuffle is still on", onMain { extraController.shuffleModeEnabled })
    }

    @Test fun theAppsOwnShuffleChangesArePublishedToControllers() {
        startQueue(5, 0, RepeatMode.Off)

        runBlocking { engine.setShuffleEnabled(true) }
        await("the controller sees shuffle on") { onMain { extraController.shuffleModeEnabled } }
        runBlocking { engine.setShuffleEnabled(false) }
        await("the controller sees shuffle off") { onMain { !extraController.shuffleModeEnabled } }
    }

    @Test fun withNoEngineTheShuffleCommandIsNotOfferedAndDoesNothing() {
        startQueue(3, 0, RepeatMode.Off)
        releaseEngine(engine)
        settle()

        assertFalse(
            "shuffle is not offered without an engine",
            onMain { extraController.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE) },
        )
        externalShuffle(true)
        settle()
        assertFalse("nothing turned shuffle on", onMain { extraController.shuffleModeEnabled })
    }
}
