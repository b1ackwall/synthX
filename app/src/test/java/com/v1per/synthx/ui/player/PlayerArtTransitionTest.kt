package com.v1per.synthx.ui.player

import androidx.compose.runtime.MonotonicFrameClock
import com.v1per.synthx.ui.player.PlayerContentColors.Companion.Dark
import com.v1per.synthx.ui.player.PlayerContentColors.Companion.Light
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerArtTransitionTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    /** 60 Hz frames in the test's virtual time; [onFrameDone] sees the state each frame leaves. */
    private class VsyncClock(private val scheduler: TestCoroutineScheduler) : MonotonicFrameClock {
        var frames = 0
        var onFrameDone: () -> Unit = {}

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            val now = scheduler.currentTime * 1_000_000L
            val vsync = (now / PERIOD_NANOS + 1) * PERIOD_NANOS
            delay((vsync - now + 999_999L) / 1_000_000L)
            frames++
            val result = onFrame(vsync)
            onFrameDone()
            return result
        }
    }

    /** One frame as drawn: the progress, the incoming picture's alpha, the colors shown. */
    private data class Frame(val progress: Float, val alpha: Float, val colors: PlayerContentColors, val from: String?)

    private data class FreezeCall(val from: String, val to: String, val alpha: Float)

    private class Harness(val transition: PlayerArtTransition<String>, val clock: VsyncClock) {
        val freezes = mutableListOf<FreezeCall>()
        val frames = mutableListOf<Frame>()

        /** Every change of the displayed colors, in order. */
        fun flips(): List<PlayerContentColors> =
            frames.map { it.colors }.zipWithNext().filter { (a, b) -> a !== b }.map { it.second }
    }

    /** "b" and "b'" are two decodes of one picture. */
    private fun samePicture(a: String, b: String) = a.removeSuffix("'") == b.removeSuffix("'")

    private fun TestScope.harness(initial: String? = "a", colors: PlayerContentColors = Light): Harness {
        val clock = VsyncClock(testScheduler)
        lateinit var harness: Harness
        val transition = PlayerArtTransition(
            initialImage = initial,
            initialColors = colors,
            scope = CoroutineScope(backgroundScope.coroutineContext + clock),
            freeze = { from, to, alpha ->
                harness.freezes += FreezeCall(from, to, alpha)
                "mix($from,$to)"
            },
            sameImage = ::samePicture,
        )
        harness = Harness(transition, clock)
        clock.onFrameDone = {
            harness.frames +=
                Frame(transition.progress.value, transition.alpha, transition.displayedColors, transition.from)
        }
        return harness
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test fun progressIsLinearInTimeAndTheAlphaIsEasedWithExactEnds() {
        assertEquals(Motion.DurationNormal, PlayerArtFadeSpec.durationMillis)
        assertEquals(0f, playerArtFadeAlpha(0f))
        assertEquals(1f, playerArtFadeAlpha(1f))
        // The flip at progress 0.5 is the time midpoint, where the eased picture is mostly new.
        val atFlip = playerArtFadeAlpha(PlayerArtFlipProgress)
        assertTrue(atFlip in 0.77f..0.79f, "alpha at the flip was $atFlip")
    }

    @Test fun theFirstArtSnapsWithoutAFade() = runTest {
        val h = harness(initial = null)
        h.transition.submit("a", Dark, animate = true)
        assertEquals("a", h.transition.to)
        assertNull(h.transition.from)
        assertSame(Dark, h.transition.displayedColors)
        advance(500)
        assertEquals(0, h.clock.frames)
    }

    @Test fun aNewPictureFadesInThreeHundredMillisecondsAndTheTextFlipsOnceAtTheMidpoint() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        runCurrent()
        assertEquals("a", h.transition.from)
        assertEquals("b", h.transition.to)
        assertEquals(0f, h.transition.progress.value)
        advance(500)

        // The blend alpha and the flip come from the same progress on every frame.
        for (frame in h.frames) {
            assertEquals(playerArtFadeAlpha(frame.progress), frame.alpha)
            assertSame(if (frame.progress >= PlayerArtFlipProgress) Dark else Light, frame.colors, "at $frame")
        }
        assertEquals(listOf(Dark), h.flips())
        assertEquals(0f, h.frames.first().progress)
        assertEquals(1f, h.frames.last().progress)
        // 300 ms at 60 Hz: the start frame plus 18 more.
        assertEquals(19, h.frames.size)
    }

    @Test fun theLastFrameIsExact() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        advance(500)
        assertEquals(1f, h.transition.progress.value)
        assertEquals(1f, h.transition.alpha)
        assertNull(h.transition.from)
        assertEquals("b", h.transition.to)
        assertSame(Dark, h.transition.displayedColors)
    }

    @Test fun aNewPictureMidFadeRestartsFromTheMixWithNoSecondAnimation() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        advance(100)
        val drawn = h.frames.last().alpha
        h.transition.submit("c", Dark, animate = true)
        runCurrent()

        assertEquals(listOf(FreezeCall("a", "b", drawn)), h.freezes)
        assertEquals("mix(a,b)", h.transition.from)
        assertEquals("c", h.transition.to)
        assertEquals(0f, h.transition.progress.value)
        val restartedAt = h.frames.size
        advance(500)

        val restarted = h.frames.drop(restartedAt)
        assertEquals(0f, restarted.first().progress)
        assertTrue(restarted.zipWithNext().all { (a, b) -> b.progress >= a.progress }, "progress went back: $restarted")
        assertEquals(19, restarted.size, "the restart runs the full 300 ms once")
        assertNull(h.transition.from)
        val framesWhenDone = h.clock.frames
        advance(1_000)
        assertEquals(framesWhenDone, h.clock.frames, "nothing else was queued")
        assertEquals(1, h.freezes.size)
    }

    @Test fun skipsFasterThan150MillisecondsNeverFlipUntilTheyStopThenFlipOnce() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        repeat(6) { i ->
            advance(140)
            h.transition.submit("c$i", if (i % 2 == 0) Light else Dark, animate = true)
        }
        runCurrent()
        assertTrue(h.flips().isEmpty(), "flipped during the burst: ${h.flips()}")
        assertSame(Light, h.transition.displayedColors)
        // Each restart froze the two pictures on screen; never more than two are drawn.
        assertEquals(6, h.freezes.size)

        // The last skip's target is Dark: one flip, 150 ms after it.
        val burstEnd = h.frames.size
        advance(500)
        assertEquals(listOf(Dark), h.flips())
        val firstDark = h.frames.indexOfFirst { it.colors === Dark }
        assertTrue(firstDark >= burstEnd + 9, "flipped ${firstDark - burstEnd} frames after the burst")
    }

    @Test fun aBurstEndingOnTheShownColorsNeverFlips() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        repeat(4) { i ->
            advance(120)
            h.transition.submit("c$i", if (i == 3) Light else Dark, animate = true)
        }
        advance(500)
        assertTrue(h.flips().isEmpty(), "flipped: ${h.flips()}")
        assertSame(Light, h.transition.displayedColors)
    }

    @Test fun interruptedBeforeTheFlipItFlipsAtTheNewMidpointWhenTheColorsDiffer() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        advance(100)
        assertSame(Light, h.transition.displayedColors)
        h.transition.submit("c", Dark, animate = true)
        runCurrent()
        val restartedAt = h.frames.size
        advance(500)
        assertEquals(listOf(Dark), h.flips())
        assertFlipsAtTheMidpointCrossing(h.frames.drop(restartedAt), Dark)
    }

    /** The first frame showing [colors] is the first one at or past the flip progress. */
    private fun assertFlipsAtTheMidpointCrossing(frames: List<Frame>, colors: PlayerContentColors) {
        val flip = frames.indexOfFirst { it.colors === colors }
        assertTrue(flip > 0, "no flip to $colors in $frames")
        assertTrue(frames[flip].progress >= PlayerArtFlipProgress, "flipped early at ${frames[flip]}")
        assertTrue(frames[flip - 1].progress < PlayerArtFlipProgress, "flipped late at ${frames[flip]}")
    }

    @Test fun interruptedBeforeTheFlipByTheShownColorsItNeverFlips() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        advance(100)
        h.transition.submit("c", Light, animate = true)
        advance(500)
        assertTrue(h.flips().isEmpty(), "flipped: ${h.flips()}")
        assertSame(Light, h.transition.displayedColors)
    }

    @Test fun interruptedAfterTheFlipItFlipsBackAtTheNewMidpoint() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        advance(200)
        assertSame(Dark, h.transition.displayedColors)
        h.transition.submit("c", Light, animate = true)
        runCurrent()
        val restartedAt = h.frames.size
        advance(500)
        assertEquals(listOf(Dark, Light), h.flips())
        assertFlipsAtTheMidpointCrossing(h.frames.drop(restartedAt), Light)
    }

    @Test fun interruptedAfterTheFlipBySameColorsItDoesNotFlipAgain() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        advance(200)
        h.transition.submit("c", Dark, animate = true)
        advance(500)
        assertEquals(listOf(Dark), h.flips())
    }

    @Test fun aDecodeOfTheRunningFadesOwnTargetIsIgnored() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        advance(100)
        val before = h.transition.progress.value
        h.transition.submit("b'", Dark, animate = true)
        runCurrent()
        assertEquals("a", h.transition.from)
        assertEquals("b", h.transition.to)
        assertEquals(before, h.transition.progress.value)
        advance(500)
        assertTrue(h.freezes.isEmpty())
        assertEquals(19, h.frames.size, "the original fade ran on, once")
        assertEquals("b", h.transition.to)
        assertNull(h.transition.from)
    }

    @Test fun theSamePictureAtRestChangesNothing() = runTest {
        val h = harness()
        h.transition.submit("a'", Light, animate = true)
        advance(500)
        assertEquals("a", h.transition.to)
        assertNull(h.transition.from)
        assertEquals(0, h.clock.frames)
    }

    @Test fun withoutAnimationItSnaps() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = false)
        assertEquals("b", h.transition.to)
        assertNull(h.transition.from)
        assertSame(Dark, h.transition.displayedColors)
        advance(500)
        assertEquals(0, h.clock.frames)
    }

    @Test fun artDisappearingMidFadeSnapsToTheFillAndLight() = runTest {
        val h = harness(colors = Dark)
        h.transition.submit("b", Dark, animate = true)
        advance(100)
        h.transition.submit(null, Light, animate = true)
        assertNull(h.transition.to)
        assertNull(h.transition.from)
        assertSame(Light, h.transition.displayedColors)
        val frames = h.clock.frames
        advance(500)
        assertEquals(frames, h.clock.frames)
    }

    @Test fun artAppearingAfterNoArtSnaps() = runTest {
        val h = harness()
        h.transition.submit(null, Light, animate = true)
        h.transition.submit("b", Dark, animate = true)
        assertEquals("b", h.transition.to)
        assertNull(h.transition.from)
        assertSame(Dark, h.transition.displayedColors)
    }

    @Test fun finishEndsARunningFadeAtItsTarget() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        advance(100)
        h.transition.finish()
        assertNull(h.transition.from)
        assertEquals("b", h.transition.to)
        assertSame(Dark, h.transition.displayedColors)
        val frames = h.clock.frames
        advance(500)
        assertEquals(frames, h.clock.frames)
    }

    @Test fun finishBeforeAFadeStartsSnapsToItsTarget() = runTest {
        val h = harness()
        h.transition.submit("b", Dark, animate = true)
        h.transition.finish()
        advance(500)
        assertEquals("b", h.transition.to)
        assertNull(h.transition.from)
        assertSame(Dark, h.transition.displayedColors)
        assertEquals(0, h.clock.frames)
    }

    @Test fun finishAtRestChangesNothing() = runTest {
        val h = harness()
        h.transition.finish()
        assertEquals("a", h.transition.to)
        assertSame(Light, h.transition.displayedColors)
    }

    @Test fun blendEndsAreExact() {
        val from = intArrayOf(0xFF102030.toInt(), 0xFF000000.toInt())
        val to = intArrayOf(0xFF302010.toInt(), 0xFFFFFFFF.toInt())
        assertTrue(from.contentEquals(blendArgbPixels(from, to, 0f)))
        assertTrue(to.contentEquals(blendArgbPixels(from, to, 1f)))
    }

    @Test fun blendMixesEachChannelAndStaysOpaque() {
        val from = intArrayOf(0xFF102030.toInt(), 0xFF000000.toInt())
        val to = intArrayOf(0xFF302010.toInt(), 0xFFFFFFFF.toInt())
        val mixed = blendArgbPixels(from, to, 0.25f)
        assertEquals(0xFF182028.toInt(), mixed[0])
        assertEquals(0xFF404040.toInt(), mixed[1])
        assertEquals(0xFF808080.toInt(), blendArgbPixels(from, to, 0.5f)[1])
    }

    private companion object {
        const val PERIOD_NANOS = 16_666_667L
    }
}
