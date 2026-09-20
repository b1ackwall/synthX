package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.stubSongRepository
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P9-T3 end to end: the real [SynthXPlaybackService], a real [ExoPlayerEngine] owning the queue,
 * and a *second* in-process [MediaController] standing in for the system. That is not a
 * simplification of the notification: media3's media-notification controller is exactly such a
 * controller, and its Next/Previous buttons call `seekToNextMediaItem` / `seekToPreviousMediaItem`
 * on it. Queue slots point at one short silent WAV, so nothing here depends on the media library.
 *
 * Runs in the debug app's process against the `.debug` package only. Persistence goes to a stub
 * repository (writes are rejected and logged), so no app queue data is touched.
 */
@RunWith(AndroidJUnit4::class)
class SystemTransportNavigationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private lateinit var wav: File
    private lateinit var persistence: QueuePersistence
    private lateinit var engine: ExoPlayerEngine
    private lateinit var system: MediaController
    private var engineReleased = false

    @Before fun setUp() {
        // The engine observes the current song's palette through the live repository, which the
        // startup gate refuses until MainActivity has run its startup. Model a ready gate, as the
        // restore instrumentation does, and hand the process its lazy production gate back after.
        DatabaseStartupGate.markReadyForTest()
        wav = File(context.cacheDir, "p9t3-silence.wav").also { writeSilentWav(it, seconds = 60) }
        persistence = QueuePersistence(stubSongRepository())
        onMain { engine = ExoPlayerEngine(context, persistence) }
        val future = onMain {
            MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, SynthXPlaybackService::class.java)),
            ).buildAsync()
        }
        system = future.get(15, TimeUnit.SECONDS)
    }

    @After fun tearDown() {
        onMain {
            system.release()
            if (!engineReleased) engine.release()
        }
        persistence.release()
        wav.delete()
        DatabaseStartupGate.resetForTest(context)
    }

    // --- rear-edge Previous under Repeat All ---------------------------------------------------

    /**
     * 120 songs, Repeat All, then the loaded window walked back so loaded index 0 is order index 50
     * (not the queue start). Native media3 wraps *within the window* here, jumping ~40 songs
     * forward; the app restarts the current song.
     */
    @Test fun systemPreviousAtTheWindowRearEdgeUnderRepeatAllRestartsInsteadOfWrapping() {
        startQueue(count = 120, startIndex = 60, repeat = RepeatMode.All)
        val rearEdgeSlot = onMain { engine.orderStore.slotAt(50)!!.slotId }

        onMain { system.seekTo(/* mediaItemIndex = */ 0, /* positionMs = */ 0L) }
        await("current slot is the loaded rear edge") {
            onMain { system.currentMediaItemIndex == 0 && engine._currentSlotId.value == rearEdgeSlot }
        }
        assertEquals("precondition: loaded index 0 is not the queue start", 50, onMain { engine._currentIndex.value })
        seekSystemTo(0L)

        onMain { system.seekToPreviousMediaItem() }
        settle()

        assertEquals(rearEdgeSlot, onMain { system.currentMediaItem?.mediaId })
        assertEquals(0, onMain { system.currentMediaItemIndex })
    }

    // --- final-item Next under Repeat One ------------------------------------------------------

    /**
     * 60 songs, Repeat One, on the final song. Native availability treats Repeat One as Repeat
     * Off, so without the availability gate Next is absent entirely (no notification button, and
     * the session would drop the command before it reached any handler).
     */
    @Test fun systemNextOnTheFinalItemUnderRepeatOneIsAvailableAndWrapsToTheBeginning() {
        startQueue(count = 60, startIndex = 59, repeat = RepeatMode.One)
        val firstSlot = onMain { engine.orderStore.slotAt(0)!!.slotId }

        await("Next is advertised to the system controller") {
            onMain { system.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
        }
        assertTrue(onMain { system.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) })

        onMain { system.seekToNextMediaItem() }

        await("system Next wrapped to the first queue slot") {
            onMain { engine._currentSlotId.value == firstSlot && system.currentMediaItem?.mediaId == firstSlot }
        }
        assertEquals(0, onMain { engine._currentIndex.value })
    }

    /** Control for the gate: with Repeat Off there genuinely is no next on the final item. */
    @Test fun systemNextIsNotOfferedOnTheFinalItemUnderRepeatOff() {
        startQueue(count = 60, startIndex = 59, repeat = RepeatMode.Off)
        settle()
        assertFalse(onMain { system.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) })
        assertFalse(onMain { system.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) })
    }

    /** Availability follows the queue: leaving the final item re-offers Next without a repeat change. */
    @Test fun systemNextAvailabilityFollowsTheCurrentItem() {
        startQueue(count = 60, startIndex = 59, repeat = RepeatMode.Off)
        onMain { system.seekTo(/* mediaItemIndex = */ 0, /* positionMs = */ 0L) }
        await("Next is offered once a later item exists") {
            onMain { system.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
        }
    }

    // --- Previous: the >3s restart rule --------------------------------------------------------

    @Test fun notificationPreviousMoreThanThreeSecondsInRestartsTheCurrentSong() {
        startQueue(count = 10, startIndex = 5, repeat = RepeatMode.Off)
        val current = onMain { engine._currentSlotId.value }

        seekSystemTo(5_000L)

        // The notification's own button command; natively this always jumps to the previous item.
        onMain { system.seekToPreviousMediaItem() }

        settleAfterOptimisticController()
        assertRestartedInPlace(current)
    }

    @Test fun notificationPreviousWithinThreeSecondsMovesToThePreviousSong() {
        startQueue(count = 10, startIndex = 5, repeat = RepeatMode.Off)
        val previousSlot = onMain { engine.orderStore.slotAt(4)!!.slotId }

        seekSystemTo(500L)
        onMain { system.seekToPreviousMediaItem() }

        await("moved to the previous song") { onMain { engine._currentSlotId.value == previousSlot } }
        assertEquals(previousSlot, onMain { system.currentMediaItem?.mediaId })
    }

    @Test fun legacyStylePreviousUsesTheSameRule() {
        startQueue(count = 10, startIndex = 5, repeat = RepeatMode.Off)
        val current = onMain { engine._currentSlotId.value }

        seekSystemTo(5_000L)
        onMain { system.seekToPrevious() }

        settleAfterOptimisticController()
        assertRestartedInPlace(current)
    }

    // --- no engine attached --------------------------------------------------------------------

    /** Must not crash or hang: system transport falls back to native behaviour. */
    @Test fun systemTransportFallsBackToNativeWhenNoEngineIsAttached() {
        startQueue(count = 10, startIndex = 5, repeat = RepeatMode.Off)
        val current = onMain { system.currentMediaItem?.mediaId }
        onMain { engine.release() }
        engineReleased = true

        onMain { system.seekToNextMediaItem() }

        await("native Next advanced the loaded window") {
            onMain { system.currentMediaItem?.mediaId != current }
        }
        assertNotEquals(current, onMain { system.currentMediaItem?.mediaId })
    }

    // --- media keys ----------------------------------------------------------------------------

    /**
     * A headset/Bluetooth Next arrives as a media key event, routed by the platform to the app's
     * session and then through the media notification controller. The routing between the
     * platform and the session is outside this app's control, so this asserts the end result.
     */
    @Test fun mediaKeyNextOnTheFinalItemUnderRepeatOneWrapsToTheBeginning() {
        startQueue(count = 60, startIndex = 59, repeat = RepeatMode.One)
        val firstSlot = onMain { engine.orderStore.slotAt(0)!!.slotId }
        await("Next is advertised") {
            onMain { system.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
        }

        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0))

        await("media-key Next wrapped to the first queue slot") {
            onMain { engine._currentSlotId.value == firstSlot }
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun startQueue(count: Int, startIndex: Int, repeat: RepeatMode) {
        val songs = (0 until count).map { index ->
            Song(
                id = "p9t3-song-$index",
                title = "Song $index",
                artist = "Artist",
                album = "Album",
                duration = 60_000L,
                filePath = Uri.fromFile(wav).toString(),
            )
        }
        runBlocking { engine.playQueue(songs, startIndex) }
        await("queue playing at $startIndex") {
            onMain {
                val slot = engine._currentSlotId.value
                slot != null && system.currentMediaItem?.mediaId == slot &&
                    engine.orderStore.indexOfSlot(slot) == startIndex
            }
        }
        onMain { engine.setRepeatMode(repeat) }
        await("repeat mode $repeat applied") { onMain { engine._repeatMode.value == repeat } }
        // Paused so the position stays put and only the commands under test move it.
        onMain { engine.pause() }
        await("paused") { onMain { !system.isPlaying } }
        // The forced window rebuild a repeat change triggers has landed once the loaded window
        // stops changing shape.
        settle()
    }

    /** Runs [block] on the main thread and returns its result. */
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun await(what: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    /**
     * Positions the (paused) player and waits until BOTH the system controller and the engine's own
     * controller report it. The engine decides restart-versus-previous from its own controller's
     * mirrored position, which lags the session by an asynchronous update — asserting before that
     * lands would test the harness's timing, not the rule.
     */
    private fun seekSystemTo(positionMs: Long) {
        onMain { system.seekTo(positionMs) }
        await("system and engine controllers both at ~${positionMs}ms") {
            onMain {
                val engineController = engine.controller ?: return@onMain false
                kotlin.math.abs(system.currentPosition - positionMs) < 300 &&
                    kotlin.math.abs(engineController.currentPosition - positionMs) < 300
            }
        }
    }

    /**
     * `MediaController.seekToPreviousMediaItem()` masks its local state optimistically — for a
     * moment it reports the previous item at position ~0 before the session's real answer lands.
     * A restart is only observable once that correction has arrived, so wait it out before
     * asserting rather than reading the masked value.
     */
    private fun settleAfterOptimisticController() = settle()

    private fun assertRestartedInPlace(slotId: String?) {
        assertEquals(slotId, onMain { engine._currentSlotId.value })
        assertEquals(slotId, onMain { system.currentMediaItem?.mediaId })
        assertTrue(
            "restarted near the start, was ${onMain { system.currentPosition }}",
            onMain { system.currentPosition } < 1_500L,
        )
    }

    /** Lets already-issued asynchronous session/controller messages land before asserting a negative. */
    private fun settle() = Thread.sleep(1_500)

    /** 8 kHz mono 16-bit PCM silence. */
    private fun writeSilentWav(file: File, seconds: Int) {
        val sampleRate = 8_000
        val dataBytes = sampleRate * 2 * seconds
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        file.outputStream().use { out ->
            out.write("RIFF".toByteArray()); out.write(le32(36 + dataBytes))
            out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
            out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
            out.write(le32(sampleRate)); out.write(le32(sampleRate * 2))
            out.write(le16(2)); out.write(le16(16))
            out.write("data".toByteArray()); out.write(le32(dataBytes))
            out.write(ByteArray(dataBytes))
        }
    }
}
