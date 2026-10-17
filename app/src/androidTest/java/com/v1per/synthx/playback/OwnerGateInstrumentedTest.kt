package com.v1per.synthx.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The service's owner gate, on the real session: playback stops when the app's last owning
 * controller leaves, and controllers flagged as media3's notification controller never count,
 * whether they connect first, connect later, or leave first. These are the device versions of the
 * connect/disconnect cases IsOwnerControllerTest re-creates on the JVM. (A controller from another
 * app cannot be connected from inside this process; that case stays with the JVM decision test.)
 *
 * [extraController] is a flagged observer connected before the engine, so the first connection to
 * the session is always a notification controller.
 */
@RunWith(AndroidJUnit4::class)
class OwnerGateInstrumentedTest : PlaybackServiceTestBase() {

    override val extraControllerHints: Bundle get() = notificationFlagHints()

    private val others = mutableListOf<MediaController>()

    @After fun releaseOthers() = onMain { others.forEach { it.release() } }

    private fun connect(hints: Bundle?): MediaController = connectExtraController(hints).also { others += it }

    private fun release(controller: MediaController) = onMain { controller.release() }

    private fun awaitStopped() = await("session stopped") {
        onMain { !extraController.isPlaying && extraController.playbackState == Player.STATE_IDLE }
    }

    @Test fun releasingOneOfTwoOwnersKeepsPlayingAndReleasingTheLastStops() {
        val secondOwner = connect(hints = null)
        startPlaying()

        release(secondOwner)
        settle()
        assertTrue("one owner left, still playing", onMain { extraController.isPlaying })

        releaseEngine()
        awaitStopped()
    }

    @Test fun aNotificationControllerLeavingNeitherStopsNorUsesUpTheOwnersCount() {
        val notification = connect(notificationFlagHints())
        startPlaying()

        release(notification)
        settle()
        assertTrue("the engine is still bound, still playing", onMain { extraController.isPlaying })

        releaseEngine()
        awaitStopped()
    }

    @Test fun notificationControllersConnectedBeforeAndAfterTheOwnerNeverKeepPlaybackAlive() {
        connect(notificationFlagHints())
        startPlaying()

        releaseEngine()

        awaitStopped()
    }
}
