package com.v1per.synthx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.v1per.synthx.ui.components.shouldPauseAmbientMotion
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI

private const val TwoPi = (2.0 * PI).toFloat()

/** Lets a capped update land up to this early, so vsync jitter does not push it a whole frame late. */
private const val CapToleranceNanos = 2_000_000L

/** Accumulated frame time that triggers a motion update: one cap interval less the tolerance. */
internal const val AltStageUpdateIntervalNanos =
    1_000_000_000L / PlayerAltStageStyle.FrameCapFps - CapToleranceNanos

/** How far ahead of the next update the loop wakes, so the update's frame is not missed. */
private const val SleepMarginNanos = 10_000_000L

/** Whole milliseconds to sleep after an update: [intervalNanos] less the wake margin, never negative. */
internal fun altStageSleepMillis(intervalNanos: Long): Long =
    ((intervalNanos - SleepMarginNanos) / 1_000_000L).coerceAtLeast(0L)

/** Sleep between motion updates at the cap rate. */
internal val AltStageSleepMillis = altStageSleepMillis(AltStageUpdateIntervalNanos)

/** Spin, orbit and base drift angles in radians, independent of the art shown. */
internal class AltStageAngles {
    val spin = FloatArray(PlayerAltStageStyle.Copies.size)
    val orbit = PlayerAltStageStyle.OrbitStartAngles.toFloatArray()
    var baseRotation = 0f
}

/** Advances spins, orbits and the base drift by [dt] seconds at the locked speeds. */
internal fun advanceAltStageAngles(angles: AltStageAngles, dt: Float) {
    PlayerAltStageStyle.Copies.forEachIndexed { index, copy ->
        val direction = if (copy.clockwise) 1f else -1f
        angles.spin[index] = (angles.spin[index] + dt * copy.spinSpeed * direction) % TwoPi
    }
    PlayerAltStageStyle.OrbitingCopies.forEachIndexed { slot, index ->
        val speed = PlayerAltStageStyle.Copies[index].orbitSpeed
        angles.orbit[slot] = (angles.orbit[slot] + dt * speed) % TwoPi
    }
    angles.baseRotation = (angles.baseRotation + dt * PlayerAltStageStyle.BaseDriftSpeed) % TwoPi
}

/**
 * Accumulates real elapsed frame time across frames and releases it once it reaches
 * [intervalNanos], so motion advances at the cap rate and at the same speed on any display rate.
 */
internal class AltStageFrameAccumulator(private val intervalNanos: Long = AltStageUpdateIntervalNanos) {
    private var started = false
    private var previous = 0L
    private var pending = 0L

    /** Seconds to advance by when an update is due on this frame, else null. The first frame only sets the baseline. */
    fun onFrame(frameTimeNanos: Long): Float? {
        if (!started) {
            started = true
            previous = frameTimeNanos
            return null
        }
        pending += frameTimeNanos - previous
        previous = frameTimeNanos
        if (pending < intervalNanos) return null
        val dt = pending / 1_000_000_000f
        pending = 0L
        return dt
    }
}

/**
 * Motion pauses with no song, while the music is not playing, while the sheet is collapsed
 * (progress below 0.1%), or under the system reduced-motion policy.
 */
internal fun shouldPausePlayerAltStageMotion(
    hasSong: Boolean,
    isPlaying: Boolean,
    progress: Float,
    systemReducedMotionActive: Boolean,
): Boolean = shouldPauseAmbientMotion(
    instancePaused = !hasSong || !isPlaying || progress < 0.001f,
    systemReducedMotionActive = systemReducedMotionActive,
)

/** Motion angles plus the frame state that only the background's draw pass reads. */
@Stable
internal class AltStageMotion {
    val angles = AltStageAngles()
    val frame = mutableLongStateOf(0L)
}

/**
 * Advances [motion] at the cap rate until cancelled. After each update it sleeps [sleepMillis],
 * then waits frame by frame until the accumulator releases the next update. The first frame
 * only sets the baseline, so a restart never applies the time spent stopped.
 */
internal suspend fun runAltStageMotion(motion: AltStageMotion, sleepMillis: Long = AltStageSleepMillis) {
    val accumulator = AltStageFrameAccumulator()
    withFrameNanos { now -> accumulator.onFrame(now) }
    while (currentCoroutineContext().isActive) {
        delay(sleepMillis)
        do {
            val updated = withFrameNanos { now ->
                accumulator.onFrame(now)?.let { dt ->
                    advanceAltStageAngles(motion.angles, dt)
                    motion.frame.longValue = now
                } != null
            }
        } while (!updated)
    }
}

/**
 * Frame-driven motion capped at [PlayerAltStageStyle.FrameCapFps]. While [paused] the loop ends
 * and the last drawn frame stays; the angles survive pauses and art changes.
 */
@Composable
internal fun rememberAltStageMotion(paused: Boolean): AltStageMotion {
    val motion = remember { AltStageMotion() }
    LaunchedEffect(motion, paused) {
        if (paused) return@LaunchedEffect
        runAltStageMotion(motion)
    }
    return motion
}
