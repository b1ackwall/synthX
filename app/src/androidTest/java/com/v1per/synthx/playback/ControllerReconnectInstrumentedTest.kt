package com.v1per.synthx.playback

import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4-T3 / R3-F09: a failed connection attempt is retried instead of being terminal — and, since
 * issue #175, a request made while that retry is still pending waits for it instead of being
 * silently dropped.
 *
 * Failed attempts are injected through [ExoPlayerEngine]'s `controllerFutureFactory` test seam; a
 * "real" attempt is the real connection to the real [SynthXPlaybackService]. A retry the test needs
 * to hold open is a [SettableFuture] the test completes with a real connection when it is ready.
 * (Disabling the service component instead does not work: `SessionToken` resolves through
 * PackageManager, so the engine *constructor* throws `IllegalArgumentException` for a disabled
 * service rather than seeing a failed connection future.)
 *
 * A mid-session disconnect is simulated by invoking the engine's own `MediaController.Listener`
 * directly, because the session cannot be made to drop a controller from the test side. That
 * leaves the old controller genuinely connected — unlike production, where media3 ignores every
 * command on it — so that test asserts on the engine's waiting behaviour, not on media3's.
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

        // The wait spans the failed attempt AND its retry (issue #175): it resolves with the
        // retried controller rather than reporting the first failure.
        val controller = runBlocking { withTimeout(10_000) { engine.awaitController() } }
        assertNotNull("awaitController must wait out the retry, not report the failed first attempt", controller)
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

    /**
     * Issue #175 itself. The first attempt fails; `playQueue` is issued while the retry is still
     * pending; the retry is then allowed to connect. Before the fix, `playQueue` returned
     * immediately having done nothing (`awaitController()` answered null the moment the first
     * attempt failed), so the loader thread finished early and nothing ever played.
     */
    @Test fun aPlayQueueIssuedWhileTheRetryIsPendingPlaysOnceItConnects() {
        val attempts = AtomicInteger()
        val retry = SettableFuture.create<MediaController>()
        val retryListener = AtomicReference<MediaController.Listener>()
        engine = newEngine { listener ->
            if (attempts.incrementAndGet() == 1) {
                Futures.immediateFailedFuture(IllegalStateException("injected: first connection attempt fails"))
            } else {
                retryListener.set(listener)
                retry
            }
        }
        // Issued after the (immediately failing) first attempt and before the held retry can
        // land. Whether the failure callback has run yet or not, the old one-shot readiness
        // signal dropped this request either way; both are the window #175 is about.
        await("first attempt made") {
            onMain { attempts.get() == 1 && engine.controller == null && engine.connection.phase.value == ConnectionPhase.Connecting }
        }

        val queue = songs(3, "backoff")
        val loader = thread(name = "backoff-queue-load") { runBlocking { engine.playQueue(queue, 0) } }

        await("the retry attempt started (and is being held open)", timeoutMs = 10_000) { attempts.get() == 2 }
        settle()
        assertTrue("playQueue must still be waiting for the connection, not have returned", loader.isAlive)
        assertTrue("nothing may be installed before a controller exists", onMain { engine.orderStore.isEmpty })

        onMain { retry.setFuture(realConnection(retryListener.get())) }
        loader.join(15_000)
        assertFalse("playQueue must complete once the retry connects", loader.isAlive)
        await("the request made during the backoff is playing") {
            onMain {
                val slot = engine._currentSlotId.value
                slot != null && extraController.currentMediaItem?.mediaId == slot && extraController.isPlaying
            }
        }
        assertEquals(2, attempts.get())
    }

    /**
     * After a whole round of attempts fails the engine used to stay dead until the Activity was
     * relaunched. A playback request now re-arms one fresh round.
     */
    @Test fun aRequestAfterGivingUpStartsAFreshRoundAndPlays() {
        val failedAttempts = ReconnectPolicy.MAX_ATTEMPTS + 1 // the first attempt plus every retry
        val attempts = AtomicInteger()
        engine = newEngine { listener ->
            if (attempts.incrementAndGet() <= failedAttempts) {
                Futures.immediateFailedFuture(IllegalStateException("injected: attempt ${attempts.get()} fails"))
            } else {
                realConnection(listener)
            }
        }
        // 1s + 2s + 4s of backoff.
        await("gave up", timeoutMs = 15_000) { onMain { engine.connection.phase.value == ConnectionPhase.GaveUp } }
        assertEquals(failedAttempts, attempts.get())

        playQueueAndAwait(songs(3, "rearmed"), 0)

        await("playing through the re-armed connection") { onMain { extraController.isPlaying } }
        assertEquals("the request itself started the new attempt — no backoff first", failedAttempts + 1, attempts.get())
    }

    /** When even the re-armed round fails, the request is surfaced rather than silently dropped. */
    @Test fun aRequestThatStillCannotConnectReportsServiceUnavailable() {
        val attempts = AtomicInteger()
        engine = newEngine { _ ->
            attempts.incrementAndGet()
            Futures.immediateFailedFuture(IllegalStateException("injected: every attempt fails"))
        }
        await("gave up", timeoutMs = 15_000) { onMain { engine.connection.phase.value == ConnectionPhase.GaveUp } }

        val error = runBlocking {
            // Subscribed before the request: errors has no replay, so a late collector misses it.
            val firstError = async(start = CoroutineStart.UNDISPATCHED) { engine.errors.first() }
            withTimeout(15_000) { engine.playQueue(songs(3, "unreachable"), 0) }
            withTimeout(1_000) { firstError.await() }
        }

        assertEquals(PlaybackError.ServiceUnavailable, error)
        assertEquals("the request re-armed exactly one more full round", 2 * (ReconnectPolicy.MAX_ATTEMPTS + 1), attempts.get())
        assertTrue(onMain { engine.orderStore.isEmpty })
    }

    /**
     * A mid-session disconnect: `controller` still holds the dropped instance through the backoff.
     * A queue load in that window must wait for the new controller rather than install itself
     * into the old one (which, in production, media3 silently ignores), and a queue insert must be
     * refused rather than reported as added.
     */
    @Test fun aRequestDuringAMidSessionReconnectWaitsForTheNewController() {
        val attempts = AtomicInteger()
        val engineListener = AtomicReference<MediaController.Listener>()
        // Forwards the session's callbacks to the engine, except for the test's own cleanup
        // release of the first controller at the end.
        val forwardToEngine = AtomicBoolean(true)
        val retry = SettableFuture.create<MediaController>()
        val retryListener = AtomicReference<MediaController.Listener>()
        engine = newEngine { listener ->
            if (attempts.incrementAndGet() == 1) {
                engineListener.set(listener)
                realConnection(object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        if (forwardToEngine.get()) listener.onDisconnected(controller)
                    }
                })
            } else {
                retryListener.set(listener)
                retry
            }
        }
        await("connected") { onMain { engine.connection.isConnected } }
        val firstController = onMain { engine.controller!! }

        forwardToEngine.set(false)
        onMain { engineListener.get().onDisconnected(firstController) }
        assertFalse("a dropped controller must not read as connected", onMain { engine.connection.isConnected })
        assertFalse("inserts are refused while reconnecting", onMain { engine.playNext(song("refused")) })

        val loader = thread(name = "reconnect-queue-load") { runBlocking { engine.playQueue(songs(3, "reconnect"), 0) } }
        await("the reconnect attempt started", timeoutMs = 10_000) { attempts.get() == 2 }
        settle()
        assertTrue("playQueue must wait for the new controller", loader.isAlive)
        assertTrue("nothing may be installed into the dropped controller", onMain { engine.orderStore.isEmpty })

        onMain { retry.setFuture(realConnection(retryListener.get())) }
        loader.join(15_000)
        assertFalse(loader.isAlive)
        await("playing through the new controller") {
            onMain {
                val slot = engine._currentSlotId.value
                engine.controller !== firstController && slot != null &&
                    extraController.currentMediaItem?.mediaId == slot && extraController.isPlaying
            }
        }
        onMain { firstController.release() }
        settle()
        assertEquals("no further reconnect was triggered", 2, attempts.get())
    }
}
