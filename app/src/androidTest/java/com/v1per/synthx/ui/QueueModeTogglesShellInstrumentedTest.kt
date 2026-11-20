package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.FakePlaybackEngine.Companion.song
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Up Next shuffle and repeat buttons in the real shell: two taps that land before the screen
 * redraws are two toggles. Each tap asks the view model to toggle or cycle, and the target comes
 * from the engine when the tap runs, not from the value the screen last drew.
 *
 * Both taps call the button's own click action, read once, inside one UI-thread block: exactly
 * what two touches handled in one frame do, with no redraw between them.
 */
@RunWith(AndroidJUnit4::class)
class QueueModeTogglesShellInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val engine = FakePlaybackEngine(song("current"), upcoming = (1..10).map { song("next-$it") })

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    /** Opens the player sheet with a slow drag up from the mini player, then enters queue mode. */
    private fun openQueueMode() {
        shell.startShell(engine)
        composeRule.waitForIdle()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val mini = composeRule.onNodeWithTag(PerfTestTags.MiniPlayer).fetchSemanticsNode().boundsInRoot.center
        val travel = composeRule.onNodeWithTag(PerfTestTags.PlayerSheet).fetchSemanticsNode().boundsInRoot.top
        composeRule.onRoot().performTouchInput {
            down(mini)
            repeat(120) {
                advanceEventTime(16)
                moveBy(Offset(0f, -travel * 0.7f / 120))
            }
            repeat(20) {
                advanceEventTime(16)
                moveBy(Offset.Zero)
            }
            up()
        }
        composeRule.waitForIdle()
        check(
            composeRule.onNodeWithTag(PerfTestTags.PlayerSheet).fetchSemanticsNode().boundsInRoot.top <
                root.height * 0.1f,
        ) {
            "the player sheet did not open"
        }
        composeRule.onNodeWithTag(PerfTestTags.QueueButton).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagDisplayed(PerfTestTags.QueueList) }
        composeRule.waitForIdle()
    }

    private fun tapTwiceInOneFrame(contentDescriptionRes: Int) {
        val description = composeRule.activity.getString(contentDescriptionRes)
        val node = composeRule.onNode(hasContentDescription(description) and hasClickAction()).fetchSemanticsNode()
        val click = checkNotNull(node.config[SemanticsActions.OnClick].action)
        composeRule.runOnUiThread {
            click()
            click()
        }
        composeRule.waitForIdle()
    }

    @Test fun twoQuickShuffleTapsAreTwoToggles() {
        openQueueMode()

        tapTwiceInOneFrame(R.string.cd_shuffle)

        composeRule.waitUntil(ShellTestHost.WAIT_MS) { engine.shuffleCalls.size >= 2 }
        assertEquals(listOf(true, false), engine.shuffleCalls.toList())
    }

    @Test fun twoQuickRepeatTapsAreTwoSteps() {
        openQueueMode()

        tapTwiceInOneFrame(R.string.cd_repeat)

        composeRule.waitUntil(ShellTestHost.WAIT_MS) { engine.repeatCalls.size >= 2 }
        assertEquals(listOf(RepeatMode.All, RepeatMode.One), engine.repeatCalls.toList())
    }
}
