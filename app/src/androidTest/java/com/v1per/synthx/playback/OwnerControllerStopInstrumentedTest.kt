package com.v1per.synthx.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end for the owner-controller fix: the real [SynthXPlaybackService], a real
 * [ExoPlayerEngine] as the only owner, and media3's real media-notification controller (which the
 * service connects on its own, under the app's package). When the only real owner goes away the
 * service must stop playback; before the fix the notification controller was counted as a second
 * owner, the count never reached zero, and the session stayed PLAYING with the position advancing.
 *
 * The observer here is deliberately connected *with the media-notification flag*. An unflagged
 * same-package controller (like the `system` controller in [SystemTransportNavigationInstrumentedTest])
 * is legitimately an owner as far as the service can tell, and would keep the count above zero and
 * mask the result whether or not the fix is present. Flagged, the fixed service excludes it exactly
 * like the real notification controller, and the unfixed service counts it — which is what makes
 * this test fail before the fix instead of passing for the wrong reason. Connecting an extra
 * flagged controller is the documented way to configure a session's platform state
 * (`MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG`).
 *
 * Bootstrap lives in [PlaybackServiceTestBase]. NOTE: no unflagged same-package "system"
 * controller is created in this class — the only same-package controllers are the engine's and
 * the flagged observer.
 */
@RunWith(AndroidJUnit4::class)
class OwnerControllerStopInstrumentedTest : PlaybackServiceTestBase() {

    override val extraControllerHints: Bundle get() = notificationFlagHints()

    private val observer: MediaController get() = extraController

    /**
     * The original probe: release the only real controller while playing. The session must leave
     * PLAYING and the position must stop advancing.
     */
    @Test fun releasingTheOnlyOwnerStopsPlaybackDespiteTheNotificationControllerStillBeingBound() {
        startPlaying()
        val startedAt = onMain { observer.currentPosition }
        await("position advances while the owner is bound") { onMain { observer.currentPosition } > startedAt + 500 }

        releaseEngine()

        await("session left PLAYING after the last owner disconnected") {
            onMain { !observer.isPlaying && observer.playbackState == Player.STATE_IDLE }
        }
        val stoppedAt = onMain { observer.currentPosition }
        Thread.sleep(1_500)
        assertFalse("still not playing", onMain { observer.isPlaying })
        assertEquals(
            "position must not keep advancing after the stop",
            stoppedAt,
            onMain { observer.currentPosition },
        )
    }

    /**
     * The disconnect-side call site. The notification controller's own disconnect must not count
     * against the engine: if only onPostConnect were filtered, this release would decrement the
     * engine's count to zero and stop playback while the engine is still legitimately bound.
     */
    @Test fun notificationControllerDisconnectingDoesNotStopPlaybackWhileTheOwnerIsBound() {
        startPlaying()
        val engineController = onMain { engine.controller!! }

        releaseExtraController()
        Thread.sleep(1_500)

        assertTrue("engine's controller still sees PLAYING", onMain { engineController.isPlaying })
        assertEquals(Player.STATE_READY, onMain { engineController.playbackState })
    }
}
