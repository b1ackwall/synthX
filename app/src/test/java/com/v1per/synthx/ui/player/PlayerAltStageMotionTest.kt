package com.v1per.synthx.ui.player

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerAltStageMotionTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private class Run(val angles: AltStageAngles, val updates: Int, val releasedSeconds: Double)

    /** Feeds evenly spaced frames for [seconds] and advances the angles exactly as the loop does. */
    private fun simulate(frameIntervalNanos: Long, seconds: Double): Run {
        val accumulator = AltStageFrameAccumulator()
        val angles = AltStageAngles()
        val start = 5_000_000_000L
        val frames = Math.round(seconds * 1e9 / frameIntervalNanos)
        var updates = 0
        var released = 0.0
        for (frame in 0..frames) {
            accumulator.onFrame(start + frame * frameIntervalNanos)?.let { dt ->
                advanceAltStageAngles(angles, dt)
                updates++
                released += dt
            }
        }
        return Run(angles, updates, released)
    }

    @Test fun updateIntervalIsOneTenthOfASecondLessTheTwoMillisecondAllowance() {
        assertEquals(100_000_000L - 2_000_000L, AltStageUpdateIntervalNanos)
    }

    @Test fun motionUpdatesTenTimesASecondAt60_90And120Hz() {
        for (interval in listOf(16_666_667L, 11_111_111L, 8_333_333L)) {
            assertEquals(20, simulate(interval, 2.0).updates, "updates at ${1e9 / interval} Hz")
        }
    }

    @Test fun motionSpeedIsIdenticalAtDifferentDisplayFrameIntervals() {
        val runs = listOf(16_666_667L, 11_111_111L, 8_333_333L).map { simulate(it, 2.0) }
        val reference = runs.first().angles
        for (run in runs.drop(1)) {
            for (i in reference.spin.indices) assertEquals(reference.spin[i], run.angles.spin[i], 1e-5f)
            for (i in reference.orbit.indices) assertEquals(reference.orbit[i], run.angles.orbit[i], 1e-5f)
            assertEquals(reference.baseRotation, run.angles.baseRotation, 1e-5f)
        }
        // Every release carries the real elapsed time, so angle / time is the locked speed at
        // any rate, including one (144 Hz) whose updates do not land exactly on 10 per second.
        for (interval in listOf(16_666_667L, 11_111_111L, 8_333_333L, 6_944_444L)) {
            val run = simulate(interval, 2.0)
            assertEquals(0.05 * run.releasedSeconds, run.angles.spin[0].toDouble(), 1e-4)
            assertEquals(-0.14 * run.releasedSeconds, run.angles.spin[3].toDouble(), 1e-4)
            assertEquals(0.06 * run.releasedSeconds, run.angles.orbit[0].toDouble(), 1e-4)
            assertEquals(PI - 0.09 * run.releasedSeconds, run.angles.orbit[1].toDouble(), 1e-4)
            assertEquals(0.03 * run.releasedSeconds, run.angles.baseRotation.toDouble(), 1e-4)
        }
    }

    @Test fun anUpdateMayLandUpToTwoMillisecondsEarly() {
        val accumulator = AltStageFrameAccumulator()
        assertNull(accumulator.onFrame(1_000L))
        assertNull(accumulator.onFrame(1_000L + AltStageUpdateIntervalNanos - 1))
        assertEquals(
            AltStageUpdateIntervalNanos / 1e9f,
            accumulator.onFrame(1_000L + AltStageUpdateIntervalNanos)!!,
            1e-7f,
        )
    }

    @Test fun jitteredSixtyHertzFramesStillUpdateEverySixthFrame() {
        // Six 16.4 ms frames are 98.4 ms: short of 1/10 s, but inside the 2 ms allowance.
        val accumulator = AltStageFrameAccumulator()
        accumulator.onFrame(0L)
        val updatedFrames = (1..18).filter { accumulator.onFrame(it * 16_400_000L) != null }
        assertEquals(listOf(6, 12, 18), updatedFrames)
    }

    @Test fun firstFrameOnlySetsTheBaseline() {
        val accumulator = AltStageFrameAccumulator()
        assertNull(accumulator.onFrame(9_000_000_000L))
        assertNull(accumulator.onFrame(9_016_666_667L))
    }

    @Test fun motionPausesWithNoSongWhileCollapsedOrUnderReducedMotion() {
        assertTrue(
            shouldPausePlayerAltStageMotion(
                hasSong = false,
                isPlaying = true,
                progress = 1f,
                systemReducedMotionActive = false,
            ),
        )
        assertTrue(
            shouldPausePlayerAltStageMotion(
                hasSong = true,
                isPlaying = true,
                progress = 0f,
                systemReducedMotionActive = false,
            ),
        )
        assertTrue(
            shouldPausePlayerAltStageMotion(
                hasSong = true,
                isPlaying = true,
                progress = 0.0009f,
                systemReducedMotionActive = false,
            ),
        )
        assertFalse(
            shouldPausePlayerAltStageMotion(
                hasSong = true,
                isPlaying = true,
                progress = 0.001f,
                systemReducedMotionActive = false,
            ),
        )
        assertFalse(
            shouldPausePlayerAltStageMotion(
                hasSong = true,
                isPlaying = true,
                progress = 1f,
                systemReducedMotionActive = false,
            ),
        )
        assertTrue(
            shouldPausePlayerAltStageMotion(
                hasSong = true,
                isPlaying = true,
                progress = 1f,
                systemReducedMotionActive = true,
            ),
        )
    }

    @Test fun motionPausesWhileTheMusicIsNotPlaying() {
        assertTrue(
            shouldPausePlayerAltStageMotion(
                hasSong = true,
                isPlaying = false,
                progress = 1f,
                systemReducedMotionActive = false,
            ),
        )
        assertFalse(
            shouldPausePlayerAltStageMotion(
                hasSong = true,
                isPlaying = true,
                progress = 1f,
                systemReducedMotionActive = false,
            ),
        )
        for (isPlaying in listOf(true, false)) {
            assertTrue(
                shouldPausePlayerAltStageMotion(
                    hasSong = true,
                    isPlaying = isPlaying,
                    progress = 0.0009f,
                    systemReducedMotionActive = false,
                ),
            )
        }
    }

    @Test fun twistNeedsApi33() {
        assertFalse(altStageTwistSupported(32))
        assertTrue(altStageTwistSupported(33))
        assertTrue(altStageTwistSupported(36))
    }

    @Test fun nothingIsDrawnOverTheBackgroundWhileTheScrimAlphaIsZero() {
        assertEquals(0f, PlayerAltStageStyle.ScrimAlpha)
        assertNull(altStageScrimColor(PlayerAltStageStyle.ScrimAlpha))
        assertEquals(0.5f, altStageScrimColor(0.5f)!!.alpha, 0.01f)
    }
}
