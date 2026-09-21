package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4-T3 / R3-F09: a failed connection attempt is retried instead of being terminal.
 *
 * The first attempt is failed through [ExoPlayerEngine]'s `controllerFutureFactory` test seam; every
 * later attempt is the real connection to the real [SynthXPlaybackService]. (Disabling the service
 * component instead does not work: `SessionToken` resolves through PackageManager, so the engine
 * *constructor* throws `IllegalArgumentException` for a disabled service rather than seeing a
 * failed connection future.)
 *
 * What is not covered: mid-session `MediaController.Listener.onDisconnected` → reconnect. The seam
 * could drive it, but the session cannot be made to drop a controller from the test side without
 * a further hook into the service.
 */
@RunWith(AndroidJUnit4::class)
class ControllerReconnectInstrumentedTest : PlaybackServiceTestBase() {
    override val createEngineInSetUp = false

    @Test fun aFailedFirstConnectionIsRetriedAndThenWorks() {
        val attempts = AtomicInteger()
        val constructedAt = System.currentTimeMillis()
        engine = newEngine { listener ->
            if (attempts.incrementAndGet() == 1) {
                Futures.immediateFailedFuture(IllegalStateException("injected: first connection attempt fails"))
            } else {
                realConnection(listener)
            }
        }

        // The failure resolves the one-shot readiness signal as "no controller" — the engine
        // reports the failed attempt rather than hanging on it.
        val controllerAfterFailure = runBlocking { withTimeout(5_000) { engine.awaitController() } }
        assertNull("the injected first attempt must not yield a controller", controllerAfterFailure)
        assertEquals("nothing has retried yet", 1, attempts.get())

        await("the retry connected", timeoutMs = 10_000) { onMain { engine.controller != null } }
        val elapsed = System.currentTimeMillis() - constructedAt
        assertEquals("exactly one retry was needed", 2, attempts.get())
        assertTrue("retry must back off (ReconnectPolicy's first delay is 1s), was ${elapsed}ms", elapsed >= 900)

        // Not merely connected: the engine drives real playback through the retried controller.
        playQueueAndAwait(songs(3, "retry"), 0)
        await("playing through the retried connection") { onMain { extraController.isPlaying } }
    }

    @Test fun aSuccessfulFirstConnectionIsNeverRetried() {
        val attempts = AtomicInteger()
        engine = newEngine { listener ->
            attempts.incrementAndGet()
            realConnection(listener)
        }

        await("connected") { onMain { engine.controller != null } }
        settle()

        assertEquals("a healthy connection must not trigger reconnects", 1, attempts.get())
    }
}
