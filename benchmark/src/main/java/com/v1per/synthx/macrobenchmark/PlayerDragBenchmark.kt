package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finger-driven expand and collapse from the Library tab, once each per iteration. The touch
 * samples are equal steps 33 ms apart (see TouchInjector for why), so at 60 Hz the sheet gets a
 * new finger position on every second frame.
 *
 * slowDrag: one second of travel across 35% of the display, a 300 ms hold, then release. The
 * sheet reads a release velocity far below its 125 dp/s fling threshold, so it settles by its
 * 20% positional threshold. Rarely flaky.
 *
 * fling: half the display in 200 ms, released while moving, which the sheet reads as a release
 * velocity above the fling threshold. Rarely flaky; it ends past the positional threshold too,
 * so a slower reading would settle the same way.
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
