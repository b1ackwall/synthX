package com.v1per.synthx.ui.player

import androidx.compose.runtime.MonotonicFrameClock
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerAltStageMotionLoopTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    /** Delivers frames on a fixed vsync grid in the test's virtual time and counts them. */
    private class VsyncClock(
        private val scheduler: TestCoroutineScheduler,
        private val periodNanos: Long,
        private val motion: AltStageMotion,
    ) : MonotonicFrameClock {
        var frames = 0
        var updates = 0

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            val now = scheduler.currentTime * 1_000_000L
            val vsync = (now / periodNanos + 1) * periodNanos
            delay((vsync - now + 999_999L) / 1_000_000L)
            frames++
            val result = onFrame(vsync)
            if (motion.frame.longValue == vsync) updates++
            return result
        }
    }

    private class Run(val frames: Int, val updates: Int)

    private fun run(periodNanos: Long, seconds: Long, sleepMillis: Long = AltStageSleepMillis): Run {
        lateinit var clock: VsyncClock
        runTest {
            val motion = AltStageMotion()
            clock = VsyncClock(testScheduler, periodNanos, motion)
            val job = launch(clock) { runAltStageMotion(motion, sleepMillis) }
            advanceTimeBy(seconds * 1_000L)
            job.cancel()
        }
        return Run(clock.frames, clock.updates)
    }

    @Test fun sleepIsTheIntervalLessTenMillisecondsInWholeMilliseconds() {
        assertEquals(90L, altStageSleepMillis(100_000_000L))
        assertEquals(54L, altStageSleepMillis(64_666_666L))
        assertEquals(0L, altStageSleepMillis(10_000_000L))
        assertEquals(0L, altStageSleepMillis(5_000_000L))
        assertEquals(0L, altStageSleepMillis(0L))
        assertEquals(altStageSleepMillis(AltStageUpdateIntervalNanos), AltStageSleepMillis)
    }

    @Test fun atSixtyHertzTheLoopWakesOnlyForTheUpdateFrames() {
        val run = run(16_666_667L, seconds = 10)
        val expected = 10 * PlayerAltStageStyle.FrameCapFps
        assertTrue(run.updates in expected - 2..expected, "updates ${run.updates}")
        // One baseline frame, then one frame per update.
        assertTrue(run.frames <= run.updates + 1, "frames ${run.frames}, updates ${run.updates}")
    }

    @Test fun atNinetyAndOneTwentyHertzTheLoopWakesAtMostTwiceAnUpdate() {
        for (period in listOf(11_111_111L, 8_333_333L)) {
            val run = run(period, seconds = 10)
            val expected = 10 * PlayerAltStageStyle.FrameCapFps
            assertTrue(run.updates in expected - 2..expected, "updates ${run.updates} at period $period")
            assertTrue(run.frames <= 2 * run.updates + 1, "frames ${run.frames} at period $period")
        }
    }

    @Test fun withoutTheSleepTheLoopWakesEveryVsync() {
        val run = run(16_666_667L, seconds = 10, sleepMillis = 0L)
        assertTrue(run.frames >= 599, "frames ${run.frames}")
    }

    @Test fun restartAfterALongStopAppliesNoCatchUp() = runTest {
        val motion = AltStageMotion()
        val clock = VsyncClock(testScheduler, 16_666_667L, motion)
        val first = launch(clock) { runAltStageMotion(motion) }
        advanceTimeBy(1_000L)
        first.cancel()
        val before = motion.angles.spin[3]
        advanceTimeBy(10_000L)
        val second = launch(clock) { runAltStageMotion(motion) }
        advanceTimeBy(200L)
        second.cancel()
        // 0.2 s at the outer copy's 0.14 rad/s, against 1.4 rad had the 10 s stop been applied.
        val moved = kotlin.math.abs(motion.angles.spin[3] - before)
        assertTrue(moved in 0.001f..0.03f, "moved $moved rad")
    }
}
