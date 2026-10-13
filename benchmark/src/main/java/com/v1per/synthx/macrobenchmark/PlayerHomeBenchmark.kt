package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The smoke scenario on the Home tab: tap the mini player to expand, system back to collapse.
 * Home's animated background keeps running under the mini player and stops only once the sheet
 * fully covers it, so this measures the sheet competing with that animation. The Library tab is
 * selected again afterwards.
 *
 * Moderately flaky: the background is time-driven, so frame counts differ between iterations;
 * if the system's animation scale is off, or the app's ambient motion policy holds the
 * background still, the result matches the Library variant instead.
 */
@RunWith(AndroidJUnit4::class)
class PlayerHomeBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun checkPreconditions() = Scenario.checkPreconditions()

    @Test
    fun homeExpandCollapse() {
        rule.runScenario(
            defaultIterations = 10,
            prepare = { AppReadiness.showHome(it) },
            beforeEach = { PlayerDriver(it).ensureCollapsed() },
        ) {
            val player = PlayerDriver(it)
            player.expand()
            player.collapse()
        }
    }
}
