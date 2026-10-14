package com.v1per.synthx.macrobenchmark

import android.os.SystemClock
import android.os.Trace
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Marks one idle window in this process's trace, which [IdleStateFrameMetric] reads. */
internal object IdleStateWindows {
    private const val SettleMs = 500L
    const val DwellMs = 4_000L

    fun section(state: String) = "bench.idle.$state"

    /** Waits out the transition's tail, then sits [DwellMs] inside the state's section. */
    fun dwell(state: String) {
        SystemClock.sleep(SettleMs)
        Trace.beginSection(section(state))
        try {
            SystemClock.sleep(DwellMs)
        } finally {
            Trace.endSection()
        }
    }
}

/**
 * Frames per second in each resting state, with no gesture inside a window: mini player paused
 * and playing on Library, expanded playing and paused, queue mode paused, and Home with the mini
 * player paused. Each iteration visits every state in that order; the transitions between them
 * are measured too but fall outside the windows. Audio plays for about ten seconds per
 * iteration. Needs an upcoming song for queue mode.
 *
 * Time-dependent by design: anything that animates for a while after launch or a track change
 * shows up in the first iterations only, so compare iterations, not just the median.
 */
@RunWith(AndroidJUnit4::class)
class PlayerIdleStatesBenchmark {
    private companion object {
        const val MiniPaused = "miniPaused"
        const val MiniPlaying = "miniPlaying"
        const val ExpandedPlaying = "expandedPlaying"
        const val ExpandedPaused = "expandedPaused"
        const val QueuePaused = "queuePaused"
        const val HomePaused = "homePaused"
        val States = listOf(
            MiniPaused, MiniPlaying, ExpandedPlaying, ExpandedPaused, QueuePaused, HomePaused,
        )
    }

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun idleStates() {
        rule.runScenario(
            defaultIterations = 4,
            extraMetrics = listOf(IdleStateFrameMetric(States)),
            prepare = {
                PlayerDriver(it).ensureExpanded()
                PlaybackDriver(it).requireNextAvailable()
                PlayerDriver(it).ensureCollapsed()
            },
            beforeEach = { Baseline.require(it) },
            cleanup = { PlaybackDriver(it).ensurePaused() },
        ) {
            visitStates(it)
        }
    }

    private fun visitStates(device: UiDevice) {
        val player = PlayerDriver(device)
        val playback = PlaybackDriver(device)
        val queue = QueueDriver(device)
        IdleStateWindows.dwell(MiniPaused)
        playback.ensurePlaying()
        IdleStateWindows.dwell(MiniPlaying)
        player.expand()
        IdleStateWindows.dwell(ExpandedPlaying)
        playback.ensurePaused()
        IdleStateWindows.dwell(ExpandedPaused)
        queue.ensureQueueMode(on = true)
        IdleStateWindows.dwell(QueuePaused)
        queue.ensureQueueMode(on = false)
        player.collapse()
        AppReadiness.showHome(device)
        IdleStateWindows.dwell(HomePaused)
    }
}
