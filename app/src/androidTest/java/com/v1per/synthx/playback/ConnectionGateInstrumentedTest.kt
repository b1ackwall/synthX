package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * While the engine reconnects to its session, the seek bar, a tap on an Up Next row and the repeat
 * button are refused with the session-unavailable message, one per call, and change nothing. Move,
 * remove and shuffle still edit the order store, and the reconnect rebuilds the window to match.
 *
 * The drop is the [ControllerReconnectInstrumentedTest] shape: the first connection is real behind a
 * forwarding listener, the engine is told it was dropped, and the first controller is released so,
 * as in production, it ignores every command. The retry is a held future, so the phase stays
 * Connecting until the test lets the reconnect land.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionGateInstrumentedTest : PlaybackServiceTestBase() {
    override val createEngineInSetUp = false

    private val errors = CopyOnWriteArrayList<PlaybackError>()
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retry = SettableFuture.create<MediaController>()
    private val retryListener = AtomicReference<MediaController.Listener>()

    @After fun stopCollecting() = collectorScope.cancel()

    /** Plays five songs paused on the first, then drops the session connection. */
    private fun playThenDropTheConnection() {
        val attempts = AtomicInteger()
        val engineListener = AtomicReference<MediaController.Listener>()
        val forwardToEngine = AtomicBoolean(true)
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
        playQueueAndAwait(songs(5, "gate"), 0)
        onMain { engine.pause() }
        await("paused") { onMain { !extraController.isPlaying } }
        settle()

        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.errors.collect { errors += it } }
        val first = onMain { engine.controller!! }
        forwardToEngine.set(false)
        onMain { engineListener.get().onDisconnected(first) }
        onMain { first.release() }
        await("the reconnect attempt is held") { attempts.get() == 2 }
        assertFalse("precondition: reconnecting", onMain { engine.connection.isConnected })
    }

    private fun reconnect() {
        onMain { retry.setFuture(realConnection(retryListener.get())) }
        await("reconnected") { onMain { engine.connection.isConnected } }
        settle()
    }

    private fun liveIds(): List<String> = onMain { engine.orderStore.live.map { it.slotId } }

    private fun awaitErrors(count: Int) {
        await("$count error message(s)") { errors.size >= count }
    }

    @Test fun theSeekBarAnUpNextTapAndRepeatAreRefusedWhileReconnecting() {
        playThenDropTheConnection()
        val idsBefore = liveIds()
        val currentBefore = onMain { engine._currentSlotId.value }

        onMain { engine.seekTo(30_000L) }
        awaitErrors(1)
        onMain { engine.seekToQueueItem(idsBefore[3]) }
        awaitErrors(2)
        onMain { engine.setRepeatMode(RepeatMode.All) }
        awaitErrors(3)
        settle()

        assertEquals(List(3) { PlaybackError.ServiceUnavailable }, errors.toList())
        assertEquals("the cursor did not move", currentBefore, onMain { engine._currentSlotId.value })
        assertEquals("the order did not change", idsBefore, liveIds())
        assertEquals("repeat did not change", RepeatMode.Off, onMain { engine._repeatMode.value })
        assertTrue("the session did not seek", onMain { extraController.currentPosition } < 10_000L)
        assertEquals(Player.REPEAT_MODE_OFF, onMain { extraController.repeatMode })
    }

    @Test fun moveRemoveAndShuffleStillEditTheQueueAndTheReconnectRepairsTheWindow() {
        playThenDropTheConnection()
        val ids = liveIds()

        onMain { engine.moveQueueItem(ids[4], 0) }
        onMain { engine.removeQueueItem(ids[2]) }
        runBlocking { engine.setShuffleEnabled(true) }

        assertEquals("move and remove landed in the store", 4, liveIds().size)
        assertFalse("removed slot is gone", ids[2] in liveIds())
        assertTrue("shuffle is on", onMain { engine._shuffleEnabled.value })

        reconnect()

        assertTrue("no message for the ungated edits", errors.isEmpty())
        assertEquals(
            "the session's timeline matches the store after the reconnect",
            liveIds(),
            onMain { loadedSlotIds(engine.controller!!) },
        )
        assertEquals(
            "and the extra controller sees the same window",
            liveIds(),
            onMain { (0 until extraController.mediaItemCount).map { extraController.getMediaItemAt(it).mediaId } },
        )
    }

    @Test fun afterTheReconnectTheSeekBarUpNextTapsAndRepeatWorkAgain() {
        playThenDropTheConnection()
        reconnect()
        val ids = liveIds()

        onMain { engine.seekToQueueItem(ids[2]) }
        await("Up Next tap jumped") { onMain { extraController.currentMediaItem?.mediaId == ids[2] } }
        onMain { engine.setRepeatMode(RepeatMode.All) }
        await("repeat applied") { onMain { engine._repeatMode.value == RepeatMode.All } }
        onMain { engine.pause() }
        seekSystemTo(20_000L)
        onMain { engine.seekTo(5_000L) }
        await("seek bar applied") { onMain { kotlin.math.abs(extraController.currentPosition - 5_000L) < 1_000L } }
        settle()

        assertTrue("no message once connected", errors.isEmpty())
    }
}
