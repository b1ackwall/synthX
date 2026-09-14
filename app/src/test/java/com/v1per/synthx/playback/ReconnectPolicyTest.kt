package com.v1per.synthx.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The retry backoff behind ExoPlayerEngine's connection-retry fix (P4-T3 / R3-F09): previously a
 * failed initial connection was terminal for the rest of the engine's lifetime despite comments
 * promising retry. Covers "a simulated initial failure gets a retry scheduled" at the level of
 * the actual policy decision.
 */
class ReconnectPolicyTest {

    @Test fun retriesWithExponentialBackoffAfterASimulatedInitialFailure() {
        // Attempt 0 is the first retry after the initial connection attempt (attempt -1, in
        // effect) fails — this must be non-null, i.e. a retry genuinely gets scheduled rather
        // than the engine giving up immediately as it did before P4-T3.
        assertEquals(1_000L, ReconnectPolicy.nextDelayMsOrNull(0))
        assertEquals(2_000L, ReconnectPolicy.nextDelayMsOrNull(1))
        assertEquals(4_000L, ReconnectPolicy.nextDelayMsOrNull(2))
    }

    @Test fun givesUpAfterMaxAttempts() {
        assertNull(ReconnectPolicy.nextDelayMsOrNull(ReconnectPolicy.MAX_ATTEMPTS))
        assertNull(ReconnectPolicy.nextDelayMsOrNull(ReconnectPolicy.MAX_ATTEMPTS + 5))
    }
}
