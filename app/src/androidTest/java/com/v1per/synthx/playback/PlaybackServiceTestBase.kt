package com.v1per.synthx.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.stubSongRepository
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Shared bootstrap for the instrumented tests that run the real [SynthXPlaybackService] with a
 * real [ExoPlayerEngine] and a second, in-process [MediaController], for example
 * [SystemTransportNavigationInstrumentedTest], [OwnerControllerStopInstrumentedTest] and the
 * lifecycle/cache tests in [PlaybackCacheInvalidationInstrumentedTest].
 *
 * Runs in the debug app's process against the `.debug` package only. Persistence goes to a stub
 * repository (writes are rejected and logged), so no app queue data is touched.
 *
 * Subclasses tune the bootstrap through the `open` members below rather than re-implementing it:
 * a test that needs to control *when* the engine (or the service connection) comes into being
 * turns the corresponding flag off and calls [newEngine] itself. Every engine made through
 * [newEngine] is released on teardown unless the test already did so.
 */
abstract class PlaybackServiceTestBase {
    /** Per-test limit; a class that overrides it says why. Override with a getter, not a field. */
    protected open val timeoutSeconds: Long get() = TestTimeouts.DEVICE_SECONDS

    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device(timeoutSeconds)

    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Connection hints for [extraController]; `null` is a plain, unflagged same-package controller. */
    protected open val extraControllerHints: Bundle? = null

    /** Whether setUp builds [engine]. Off for tests that construct their engines by hand. */
    protected open val createEngineInSetUp: Boolean = true

    /** Whether setUp connects [extraController]. Off for tests that need the service untouched. */
    protected open val connectExtraControllerInSetUp: Boolean = true

    protected lateinit var wav: File
    protected lateinit var persistence: QueuePersistence
    protected lateinit var engine: ExoPlayerEngine
    protected lateinit var extraController: MediaController

    private val engines = mutableListOf<ExoPlayerEngine>()
    private val releasedEngines = mutableSetOf<ExoPlayerEngine>()
    private var extraControllerReleased = false

    @Before fun setUpPlaybackBase() {
        // The engine's cache reconciliation reads the live repository, which the startup gate
        // refuses until MainActivity has run its startup. Model a ready gate, as the restore
        // instrumentation does, and hand the process its lazy production gate back after.
        DatabaseStartupGate.markReadyForTest()
        wav = File(context.cacheDir, "playback-test-silence.wav").also { writeSilentWav(it, seconds = 60) }
        persistence = createPersistence()
        if (connectExtraControllerInSetUp) {
            extraController = connectExtraController(extraControllerHints)
            resetSharedSession()
        }
        // After the reset, so an engine that adopts the session sees a clean one.
        if (createEngineInSetUp) engine = newEngine()
    }

    @After fun tearDownPlaybackBase() {
        onMain {
            if (::extraController.isInitialized && !extraControllerReleased) extraController.release()
            engines.filter { it !in releasedEngines }.forEach { it.release() }
        }
        persistence.release()
        wav.delete()
        DatabaseStartupGate.resetForTest(context)
    }

    /** The persistence every engine from [newEngine] writes through; default rejects every write. */
    protected open fun createPersistence(): QueuePersistence = QueuePersistence(stubSongRepository())

    // --- bootstrapping -------------------------------------------------------------------------

    /**
     * Builds an engine on the main thread and registers it for teardown. [controllerFutureFactory]
     * is the engine's test seam for the session connection (see its kdoc); `null` is production.
     */
    protected fun newEngine(
        controllerFutureFactory: ((MediaController.Listener) -> ListenableFuture<MediaController>)? = null,
    ): ExoPlayerEngine =
        onMain { ExoPlayerEngine(context, persistence, controllerFutureFactory) }.also { engines += it }

    /** The real connection the engine would make, for a [newEngine] factory that wants to delegate. */
    protected fun realConnection(listener: MediaController.Listener): ListenableFuture<MediaController> =
        MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, SynthXPlaybackService::class.java)),
        ).setListener(listener).buildAsync()

    /** Releases [target] now (idempotent with teardown). */
    protected fun releaseEngine(target: ExoPlayerEngine = engine) {
        onMain { target.release() }
        releasedEngines += target
    }

    /**
     * The service (and its player) is process-wide and outlives each test's engine, so whatever the
     * previous test left behind — a loaded timeline, a Repeat One — would be adopted by this test's
     * engine exactly as a relaunched Activity adopts a running session. Start from an empty, Repeat
     * Off session so results do not depend on test order.
     */
    private fun resetSharedSession() {
        onMain {
            extraController.stop()
            extraController.clearMediaItems()
            extraController.repeatMode = Player.REPEAT_MODE_OFF
        }
        await("shared session reset") {
            onMain { extraController.mediaItemCount == 0 && extraController.repeatMode == Player.REPEAT_MODE_OFF }
        }
    }

    protected fun releaseExtraController() {
        onMain { extraController.release() }
        extraControllerReleased = true
    }

    protected fun connectExtraController(hints: Bundle? = null): MediaController {
        val future = onMain {
            MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, SynthXPlaybackService::class.java)),
            ).apply { if (hints != null) setConnectionHints(hints) }.buildAsync()
        }
        return future.get(15, TimeUnit.SECONDS)
    }

    /** A controller flagged the way media3's own media-notification controller is. */
    @OptIn(markerClass = [UnstableApi::class])
    protected fun notificationFlagHints() = Bundle().apply {
        putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true)
    }

    // --- fixtures ------------------------------------------------------------------------------

    /**
     * One song backed by the shared silent WAV. Reusing an [id] gives the duplicate-id fixture.
     *
     * The URI carries the id as a query parameter: every song still plays the same file (file
     * data sources read the path only), but each id gets its own `file_path` string, which is what
     * the real `songs` table's unique index requires when [RealDbSongSeed] mirrors these songs.
     */
    protected fun song(id: String, title: String = id): Song = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        duration = 60_000L,
        filePath = Uri.fromFile(wav).buildUpon().appendQueryParameter("id", id).build().toString(),
    )

    /** [count] songs with distinct ids `"$idPrefix-$index"`. */
    protected fun songs(count: Int, idPrefix: String = "song"): List<Song> =
        (0 until count).map { song("$idPrefix-$it", "Song $it") }

    /**
     * Songs whose ids are given verbatim, in order — so `songsWithIds("A", "B", "B")` is a queue
     * with two independent slots of the *same* song id (the duplicate-occurrence case).
     */
    protected fun songsWithIds(vararg ids: String): List<Song> = ids.map { song(it) }

    // --- driving the queue ---------------------------------------------------------------------

    /** Loads [queue] on [engine] and waits until the extra controller and the engine agree on it. */
    protected fun playQueueAndAwait(queue: List<Song>, startIndex: Int) {
        runBlocking { engine.playQueue(queue, startIndex) }
        await("queue playing at $startIndex") {
            onMain {
                val slot = engine._currentSlotId.value
                slot != null && extraController.currentMediaItem?.mediaId == slot &&
                    engine.orderStore.indexOfSlot(slot) == startIndex
            }
        }
    }

    /** Starts a 3-song queue and waits until it is audibly playing. */
    protected fun startPlaying() {
        playQueueAndAwait(songs(3, "playing"), 0)
        await("playing") { onMain { extraController.isPlaying } }
    }

    /** Starts [count] songs at [startIndex] under [repeat], then pauses so only test commands move it. */
    protected fun startQueue(count: Int, startIndex: Int, repeat: RepeatMode) {
        playQueueAndAwait(songs(count), startIndex)
        onMain { engine.setRepeatMode(repeat) }
        await("repeat mode $repeat applied") { onMain { engine._repeatMode.value == repeat } }
        // Paused so the position stays put and only the commands under test move it.
        onMain { engine.pause() }
        await("paused") { onMain { !extraController.isPlaying } }
        // The forced window rebuild a repeat change triggers has landed once the loaded window
        // stops changing shape.
        settle()
    }

    /**
     * Positions the (paused) player and waits until BOTH the extra controller and the engine's own
     * controller report it. The engine decides restart-versus-previous from its own controller's
     * mirrored position, which lags the session by an asynchronous update — asserting before that
     * lands would test the harness's timing, not the rule.
     */
    protected fun seekSystemTo(positionMs: Long) {
        onMain { extraController.seekTo(positionMs) }
        await("extra and engine controllers both at ~${positionMs}ms") {
            onMain {
                val engineController = engine.controller ?: return@onMain false
                kotlin.math.abs(extraController.currentPosition - positionMs) < 300 &&
                    kotlin.math.abs(engineController.currentPosition - positionMs) < 300
            }
        }
    }

    // --- timing / threading --------------------------------------------------------------------

    /** Runs [block] on the main thread and returns its result. */
    protected fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    protected fun await(what: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    /**
     * Lets already-issued asynchronous work land before asserting a negative: waits until the main
     * looper is idle, every accepted queue write has finished (no finite restore permit is held),
     * and what every live controller reports has not changed for [SETTLE_QUIET_MS]. Fails if that
     * never happens within [SETTLE_LIMIT_MS].
     */
    protected fun settle() {
        val deadline = SystemClock.uptimeMillis() + SETTLE_LIMIT_MS
        var last: List<Any?>? = null
        var quietSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val writesInFlight =
                RestoreCoordinator.activeOperationCountForTest() - RestoreCoordinator.activeReactiveOperationCountForTest()
            val state = onMain { liveControllers().map(::controllerState) } + writesInFlight
            val now = SystemClock.uptimeMillis()
            if (state != last) {
                last = state
                quietSince = now
            } else if (writesInFlight == 0 && now - quietSince >= SETTLE_QUIET_MS) {
                return
            }
            Thread.sleep(25)
        }
        throw AssertionError("settle(): still changing after $SETTLE_LIMIT_MS ms, last state $last")
    }

    private fun liveControllers(): List<MediaController> = buildList {
        if (::extraController.isInitialized && !extraControllerReleased) add(extraController)
        engines.filter { it !in releasedEngines }.mapNotNullTo(this) { it.controller }
    }

    /** Everything a negative assertion could depend on, except the playing position. */
    private fun controllerState(c: MediaController): List<Any?> = listOf(
        c.isConnected, c.mediaItemCount, c.currentMediaItemIndex, c.currentMediaItem?.mediaId,
        c.playbackState, c.playWhenReady, c.isPlaying, c.repeatMode, c.playerError?.errorCode,
    )

    private companion object {
        // Never shorter than the fixed 1.5 s wait this replaced.
        const val SETTLE_QUIET_MS = 1_500L
        const val SETTLE_LIMIT_MS = 10_000L
    }

    // --- audio fixture -------------------------------------------------------------------------

    /** 8 kHz mono 16-bit PCM silence. */
    protected fun writeSilentWav(file: File, seconds: Int) {
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
