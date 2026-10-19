package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Refined instant cut": a screen change has no slide or fade, so the old screen is gone within
 * a few frames. Counted on the paused test clock, never with timing guesses. Clean code takes
 * about 4 frames (Navigation's own content swap); a default fade takes well over 40.
 */
@RunWith(AndroidJUnit4::class)
class InstantCutInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun framesUntil(done: () -> Boolean): Int {
        var frames = 0
        while (frames < FRAME_LIMIT && !done()) {
            composeRule.mainClock.advanceTimeByFrame()
            frames++
        }
        return frames
    }

    @Test fun tabSwitchIsAnInstantCut() {
        shell.startShell()
        assertTrue(shell.tagCount(PerfTestTags.HomeScreen) > 0)
        composeRule.mainClock.autoAdvance = false
        try {
            shell.tab(shell.context.getString(R.string.nav_library)).performClick()
            val frames = framesUntil {
                shell.tagCount(PerfTestTags.HomeScreen) == 0 && shell.tagCount(PerfTestTags.LibraryScreen) > 0
            }
            assertTrue("tab cut took $frames frames, limit $MAX_FRAMES", frames <= MAX_FRAMES)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test fun backIsAnInstantCut() {
        shell.startShell()
        shell.navigate(Screen.Library.route)
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { shell.navController.popBackStack() }
            val frames = framesUntil {
                shell.tagCount(PerfTestTags.LibraryScreen) == 0 && shell.tagCount(PerfTestTags.HomeScreen) > 0
            }
            assertTrue("pop cut took $frames frames, limit $MAX_FRAMES", frames <= MAX_FRAMES)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private companion object {
        /** About 100 ms at 60 Hz. */
        const val MAX_FRAMES = 6
        const val FRAME_LIMIT = 60
    }
}
