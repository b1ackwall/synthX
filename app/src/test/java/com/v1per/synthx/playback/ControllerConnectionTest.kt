package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #175: the connection rules behind [ExoPlayerEngine.awaitController]. The real
 * connect/retry plumbing is covered against the real service by
 * ControllerReconnectInstrumentedTest; this pins the state machine itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerConnectionTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun startsConnecting() {
        val connection = ControllerConnection()
        assertEquals(ConnectionPhase.Connecting, connection.phase.value)
        assertFalse(connection.isConnected)
    }

    /** The heart of #175: a failed attempt with a retry pending must keep a waiter waiting. */
    @Test fun aWaiterIsHeldThroughAFailedAttemptAndReleasedByTheRetrysSuccess() = runTest {
        val connection = ControllerConnection()
        val waiter = async { connection.awaitSettled() }
        runCurrent()

        assertEquals(1_000L, connection.onConnectionLost(), "first failure schedules the 1s retry")
        runCurrent()
        assertFalse(waiter.isCompleted, "a request issued during the backoff must not be answered yet")

        connection.onConnected()
        runCurrent()
        assertEquals(ConnectionPhase.Connected, waiter.await())
        assertTrue(connection.isConnected)
    }

    @Test fun aRequestIssuedMidBackoffStillWaitsForTheRetry() = runTest {
        val connection = ControllerConnection()
        connection.onConnectionLost()

        val waiter = async { connection.awaitSettled() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        connection.onConnected()
        runCurrent()
        assertEquals(ConnectionPhase.Connected, waiter.await())
    }

    @Test fun aMidSessionDisconnectMakesNewRequestsWaitForTheReconnect() = runTest {
        val connection = ControllerConnection()
        connection.onConnected()
        assertEquals(ConnectionPhase.Connected, connection.awaitSettled())

        connection.onConnectionLost()
        assertFalse(connection.isConnected, "a dropped controller must not read as connected")
        val waiter = async { connection.awaitSettled() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        connection.onConnected()
        runCurrent()
        assertEquals(ConnectionPhase.Connected, waiter.await())
    }

    @Test fun backoffFollowsReconnectPolicyThenGivesUpAndReleasesWaiters() = runTest {
        val connection = ControllerConnection()
        val waiter = async { connection.awaitSettled() }
        runCurrent()

        assertEquals(1_000L, connection.onConnectionLost())
        assertEquals(2_000L, connection.onConnectionLost())
        assertEquals(4_000L, connection.onConnectionLost())
        runCurrent()
        assertFalse(waiter.isCompleted, "still retrying — must not give up early")

        assertNull(connection.onConnectionLost(), "the round is used up")
        runCurrent()
        assertEquals(ConnectionPhase.GaveUp, waiter.await(), "a waiter must fail, not hang")
    }

    @Test fun aSuccessfulConnectionResetsTheBackoff() {
        val connection = ControllerConnection()
        connection.onConnectionLost()
        connection.onConnectionLost()
        connection.onConnected()

        assertEquals(1_000L, connection.onConnectionLost(), "a later disconnect gets a full round again")
    }

    @Test fun rearmingAfterGivingUpStartsAFreshRound() = runTest {
        val connection = ControllerConnection()
        repeat(ReconnectPolicy.MAX_ATTEMPTS + 1) { connection.onConnectionLost() }
        assertEquals(ConnectionPhase.GaveUp, connection.phase.value)

        assertTrue(connection.rearmIfGaveUp(), "the caller must start an attempt")
        assertEquals(ConnectionPhase.Connecting, connection.phase.value)
        val waiter = backgroundScope.async { connection.awaitSettled() }
        runCurrent()
        assertFalse(waiter.isCompleted, "a re-armed request waits for the new round")
        assertEquals(1_000L, connection.onConnectionLost(), "backoff starts over")
    }

    @Test fun rearmIsANoOpUnlessGivenUp() {
        val connection = ControllerConnection()
        assertFalse(connection.rearmIfGaveUp(), "already connecting")
        connection.onConnected()
        assertFalse(connection.rearmIfGaveUp(), "connected")
        assertEquals(ConnectionPhase.Connected, connection.phase.value)
    }

    @Test fun releaseFailsWaitersAndIsTerminal() = runTest {
        val connection = ControllerConnection()
        val waiter = async { connection.awaitSettled() }
        runCurrent()

        connection.release()
        runCurrent()
        assertEquals(ConnectionPhase.GaveUp, waiter.await())
        assertTrue(connection.isReleased)

        connection.onConnected()
        assertNull(connection.onConnectionLost())
        assertFalse(connection.rearmIfGaveUp(), "a released engine never reconnects")
        assertEquals(ConnectionPhase.GaveUp, connection.phase.value)
    }
}
