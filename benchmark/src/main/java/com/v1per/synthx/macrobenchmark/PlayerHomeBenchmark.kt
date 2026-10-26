package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The expand/collapse smoke scenario on the Home tab: select Home once, then per iteration tap the
 * mini player to expand the sheet and press system back to collapse it. Home fills with the solid
 * `Background` colour and the wordmark, so nothing animated draws under the mini player; this
 * measures the sheet over a static screen. The shared baseline selects the Library tab again after
 * the run.
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
