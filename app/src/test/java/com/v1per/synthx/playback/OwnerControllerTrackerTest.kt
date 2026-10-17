package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decision logic behind SynthXPlaybackService's P4-T3 fix: stop playback when the last
 * owning controller (this app's own ExoPlayerEngine) disconnects, matching the existing
 * onTaskRemoved precedent of stopping rather than continuing ownerless playback. Covers the
 * "Activity finishes while playing" scenario at the level of its actual decision — the real
 * Media3/MediaSession plumbing around it is not exercised here (no Robolectric/Mockito in this
 * module's JVM unit test setup), see the P4 report for that descope.
 */
class OwnerControllerTrackerTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun disconnectingTheOnlyOwnerReportsStop() {
        val tracker = OwnerControllerTracker()
        tracker.connected()

        assertTrue(tracker.disconnected(), "the only owner disconnecting must trigger a stop")
    }

    @Test fun disconnectingWhileAnotherOwnerRemainsDoesNotStop() {
        val tracker = OwnerControllerTracker()
        tracker.connected()
        tracker.connected()

        assertFalse(tracker.disconnected(), "one of two owners disconnecting must not stop yet")
        assertTrue(tracker.disconnected(), "the last remaining owner disconnecting must stop")
    }

    @Test fun disconnectWithNoPriorConnectNeverStops() {
        val tracker = OwnerControllerTracker()

        assertFalse(
            tracker.disconnected(),
            "a spurious disconnect with no owner ever connected must not stop playback that never had one",
        )
    }

    @Test fun countNeverGoesNegative() {
        val tracker = OwnerControllerTracker()
        tracker.connected()
        tracker.disconnected() // count -> 0, reports stop
        tracker.disconnected() // spurious extra callback; must floor at 0, not underflow to -1

        // If the count had underflowed, a single subsequent connect would still read as "not
        // the only owner" and a following disconnect would wrongly report "not yet zero".
        tracker.connected()
        assertTrue(
            tracker.disconnected(),
            "a fresh connect after floored-at-zero disconnects must still report stop on its own disconnect",
        )
    }
}
