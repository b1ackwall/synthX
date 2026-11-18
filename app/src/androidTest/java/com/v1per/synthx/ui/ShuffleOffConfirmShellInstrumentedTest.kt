package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.components.SHUFFLE_OFF_CONFIRM_DIALOG_TAG
import com.v1per.synthx.ui.components.SHUFFLE_OFF_DISMISS_BUTTON_TAG
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real shell hosts the shuffle-off question: a guarded shuffle-off from anywhere raises it. */
@RunWith(AndroidJUnit4::class)
class ShuffleOffConfirmShellInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    @Test fun theShellShowsTheQuestionAndKeepShuffleOnClosesIt() {
        val engine = FakePlaybackEngine(current = FakePlaybackEngine.song("playing")).apply {
            shuffleState.value = true
            reorderedState.value = true
        }
        val viewModel = shell.startShell(engine)

        composeRule.runOnUiThread { viewModel.setShuffleEnabled(false) }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagCount(SHUFFLE_OFF_CONFIRM_DIALOG_TAG) > 0 }
        composeRule.onNodeWithTag(SHUFFLE_OFF_DISMISS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodes(hasTestTag(SHUFFLE_OFF_CONFIRM_DIALOG_TAG)).fetchSemanticsNodes().size)
        assertTrue(engine.shuffleCalls.isEmpty())
    }
}
