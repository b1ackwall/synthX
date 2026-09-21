package com.v1per.synthx.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * Runs in the debug app's process against the `.debug` package only. Persistence goes to a stub
 * repository, so no app queue data is touched.
 */
@RunWith(AndroidJUnit4::class)
class OwnerControllerStopInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private lateinit var wav: File
    private lateinit var persistence: QueuePersistence
    private lateinit var engine: ExoPlayerEngine
    private lateinit var observer: MediaController
    private var engineReleased = false
    private var observerReleased = false

    @Before fun setUp() {
        DatabaseStartupGate.markReadyForTest()
        wav = File(context.cacheDir, "owner-stop-silence.wav").also { writeSilentWav(it, seconds = 60) }
        persistence = QueuePersistence(stubSongRepository())
        onMain { engine = ExoPlayerEngine(context, persistence) }
        // NOTE: no unflagged same-package "system" controller is created in this class — see the
        // class kdoc. The only same-package controllers are the engine's and the flagged observer.
        val hints = Bundle().apply {
            putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true)
        }
        val future = onMain {
            MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, SynthXPlaybackService::class.java)),
            ).setConnectionHints(hints).buildAsync()
        }
        observer = future.get(15, TimeUnit.SECONDS)
    }

    @After fun tearDown() {
        onMain {
            if (!observerReleased) observer.release()
            if (!engineReleased) engine.release()
        }
        persistence.release()
        wav.delete()
        DatabaseStartupGate.resetForTest(context)
    }

    /**
     * The original probe: release the only real controller while playing. The session must leave
     * PLAYING and the position must stop advancing.
     */
    @Test fun releasingTheOnlyOwnerStopsPlaybackDespiteTheNotificationControllerStillBeingBound() {
        startPlaying()
        val startedAt = onMain { observer.currentPosition }
        await("position advances while the owner is bound") { onMain { observer.currentPosition } > startedAt + 500 }

        onMain { engine.release() }
        engineReleased = true

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

        onMain { observer.release() }
        observerReleased = true
        Thread.sleep(1_500)

        assertTrue("engine's controller still sees PLAYING", onMain { engineController.isPlaying })
        assertEquals(Player.STATE_READY, onMain { engineController.playbackState })
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun startPlaying() {
        val songs = (0 until 3).map { index ->
            Song(
                id = "owner-stop-song-$index",
                title = "Song $index",
                artist = "Artist",
                album = "Album",
                duration = 60_000L,
                filePath = Uri.fromFile(wav).toString(),
            )
        }
        runBlocking { engine.playQueue(songs, 0) }
        await("queue playing") { onMain { observer.isPlaying && observer.currentMediaItem != null } }
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
