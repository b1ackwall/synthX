package com.v1per.synthx.ui.components

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Restore the original order?" question for a shuffled queue the user reordered by hand:
 * Confirm is the only answer that reaches the engine, Keep shuffle on and Back leave shuffle, the
 * flag and the order alone, and the question survives the activity being recreated because the
 * ViewModel holds it. Recreation follows CreatePlaylistRecreationInstrumentedTest's pattern.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ShuffleOffConfirmDialogInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val engine = FakePlaybackEngine().apply {
        shuffleState.value = true
        reorderedState.value = true
    }

    private lateinit var viewModel: PlaybackViewModel

    @Composable
    private fun Host() {
        viewModel = viewModel(
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlaybackViewModel(PlaybackController(engine)) as T
            },
        )
        SynthXTheme { ShuffleOffConfirmationHost(viewModel) }
    }

    /** The one call site both installs share. */
    private fun install() {
        composeRule.runOnUiThread { composeRule.activity.setContent { Host() } }
        composeRule.waitForIdle()
    }

    private fun askToTurnShuffleOff() {
        composeRule.runOnUiThread { viewModel.setShuffleEnabled(false) }
        composeRule.waitUntilExactlyOneExists(hasTestTag(SHUFFLE_OFF_CONFIRM_DIALOG_TAG))
    }

    private fun dialogShown() =
        composeRule.onAllNodes(hasTestTag(SHUFFLE_OFF_CONFIRM_DIALOG_TAG)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theQuestionShowsItsTextAndConfirmTurnsShuffleOffOnce() {
        install()
        askToTurnShuffleOff()
        composeRule.onNode(hasText(context.getString(R.string.shuffle_off_confirm_title))).assertExists()
        composeRule.onNode(hasText(context.getString(R.string.shuffle_off_confirm_message))).assertExists()
        assertEquals("nothing reaches the engine before an answer", emptyList<Boolean>(), engine.shuffleCalls)

        composeRule.onNodeWithTag(SHUFFLE_OFF_CONFIRM_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(false), engine.shuffleCalls)
        assertFalse(dialogShown())
    }

    @Test fun keepShuffleOnLeavesShuffleTheFlagAndTheOrderAlone() {
        install()
        askToTurnShuffleOff()

        composeRule.onNodeWithTag(SHUFFLE_OFF_DISMISS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue(engine.shuffleCalls.isEmpty())
        assertFalse(dialogShown())
        assertTrue(engine.shuffleState.value)
        assertTrue(viewModel.shuffleReorderedByUser.value)
    }

    @Test fun backDismissesLikeKeepShuffleOn() {
        install()
        askToTurnShuffleOff()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()

        assertTrue(engine.shuffleCalls.isEmpty())
        assertFalse(dialogShown())
        assertTrue(engine.reorderedState.value)
    }

    @Test fun thePendingQuestionSurvivesTheActivityBeingRecreated() {
        install()
        askToTurnShuffleOff()

        composeRule.activityRule.scenario.recreate()
        install()

        composeRule.waitUntilExactlyOneExists(hasTestTag(SHUFFLE_OFF_CONFIRM_DIALOG_TAG))
        assertTrue(engine.shuffleCalls.isEmpty())
        composeRule.onNodeWithTag(SHUFFLE_OFF_CONFIRM_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(false), engine.shuffleCalls)
    }

    @Test fun noQuestionWithoutAReorder() {
        engine.reorderedState.value = false
        install()

        composeRule.runOnUiThread { viewModel.setShuffleEnabled(false) }
        composeRule.waitUntil(5_000) { engine.shuffleCalls.isNotEmpty() }

        assertEquals(listOf(false), engine.shuffleCalls)
        assertFalse(dialogShown())
    }
}
