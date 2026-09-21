package com.v1per.synthx.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The owner decision behind SynthXPlaybackService's "stop when the last owning controller
 * disconnects" fix. The original filter was package-name-only, which counted media3's in-process
 * media-notification controller (same package as the app) as an owner, so the owner count never
 * reached zero and the stop never fired.
 *
 * This is the JVM-level half: the decision function on its own, and the connect/disconnect
 * pairing driven through the real [OwnerControllerTracker]. The end-to-end proof against a real
 * session is OwnerControllerStopInstrumentedTest.
 */
class IsOwnerControllerTest {

    private val app = "com.v1per.synthx.debug"

    @Test fun ownPackageControllerIsAnOwner() {
        assertTrue(
            isOwnerController(packageName = app, ownPackage = app, isNotificationController = false),
            "the app's own ExoPlayerEngine controller must be counted as an owner",
        )
    }

    @Test fun ownPackageNotificationControllerIsNotAnOwner() {
        assertFalse(
            isOwnerController(packageName = app, ownPackage = app, isNotificationController = true),
            "media3's notification controller shares the app package and must not be counted",
        )
    }

    @Test fun otherPackageControllerIsNotAnOwner() {
        assertFalse(isOwnerController("com.android.systemui", app, isNotificationController = false))
        assertFalse(isOwnerController("com.google.android.projection.gearhead", app, isNotificationController = false))
    }

    /** Package must match exactly — a prefix/suffix relative (e.g. prod vs .debug) is not an owner. */
    @Test fun lookalikePackageIsNotAnOwner() {
        assertFalse(isOwnerController("com.v1per.synthx", app, isNotificationController = false))
        assertFalse(isOwnerController("$app.other", app, isNotificationController = false))
    }

    // --- the callback pairing, driven through the real tracker ---------------------------------

    /** Mirrors the service's two callbacks: both go through the same [isOwnerController] gate. */
    private class Session(private val app: String) {
        val tracker = OwnerControllerTracker()

        fun connect(packageName: String, notification: Boolean) {
            if (isOwnerController(packageName, app, notification)) tracker.connected()
        }

        /** Returns true when the service would call `player.stop()`. */
        fun disconnect(packageName: String, notification: Boolean): Boolean {
            if (!isOwnerController(packageName, app, notification)) return false
            return tracker.disconnected()
        }
    }

    @Test fun lastRealOwnerDisconnectingStopsEvenThoughTheNotificationControllerIsStillBound() {
        val session = Session(app)
        session.connect(app, notification = false) // ExoPlayerEngine
        session.connect(app, notification = true) // media3 notification controller

        assertTrue(
            session.disconnect(app, notification = false),
            "the notification controller must not keep the owner count above zero",
        )
    }

    @Test fun notificationControllerDisconnectingNeitherDecrementsNorStops() {
        val session = Session(app)
        session.connect(app, notification = false)
        session.connect(app, notification = true)

        assertFalse(
            session.disconnect(app, notification = true),
            "the notification controller dropping must not stop playback while the engine is bound",
        )
        // Had that disconnect decremented the engine's count, this would now report "not the last
        // owner" (count already 0 -> floored) or the stop would have fired above; it must still
        // be the engine's own disconnect that stops.
        assertTrue(session.disconnect(app, notification = false), "the engine's disconnect must still stop")
    }

    @Test fun notificationControllerConnectingFirstDoesNotCountAsTheOwner() {
        val session = Session(app)
        session.connect(app, notification = true) // media3 can bind before the engine does

        assertFalse(
            session.disconnect(app, notification = true),
            "a notification-only session never had an owner, so its disconnect must not stop anything",
        )
    }

    @Test fun otherPackageControllersNeverAffectTheOwnerCount() {
        val session = Session(app)
        session.connect(app, notification = false)
        session.connect("com.android.systemui", notification = false)

        assertFalse(session.disconnect("com.android.systemui", notification = false))
        assertTrue(session.disconnect(app, notification = false))
    }
}
