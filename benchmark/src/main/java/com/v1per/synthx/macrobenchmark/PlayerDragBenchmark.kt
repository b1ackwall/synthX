package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finger-driven expand and collapse from the Library tab, once each per iteration.
 *
 * slowDrag: about one second of travel across 35% of the display, a 300 ms hold, then release.
 * The hold makes the release velocity zero, so the sheet settles by its 20% positional threshold
 * and not by fling velocity. Likely flaky: UiAutomator's step timing varies with injection
 * latency, so the drag lasts between one and two seconds, and a ROM that drops the stationary
 * moves of the hold turns the release into a slow fling (same end state, different settle).
 *
 * fling: a 60 ms swipe across 25% of the display. Moderately flaky: the injected velocity varies,
 * which changes the spring's starting speed but not where the sheet ends.
 *
 * Both start below the status bar and above the system gesture area. A ROM edge gesture (side
 * panel, one-handed mode) that claims a vertical swipe would fail the expanded/collapsed check.
 */
@RunWith(AndroidJUnit4::class)
class PlayerDragBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun slowDrag() {
        rule.runScenario(
            defaultIterations = 10,
            beforeEach = { PlayerDriver(it).ensureCollapsed() },
        ) {
            val gestures = PlayerGestures(it)
            gestures.slowDragExpand()
            gestures.slowDragCollapse()
        }
    }

    @Test
    fun fling() {
        rule.runScenario(
            defaultIterations = 10,
            beforeEach = { PlayerDriver(it).ensureCollapsed() },
        ) {
            val gestures = PlayerGestures(it)
            gestures.flingExpand()
            gestures.flingCollapse()
        }
    }
}
