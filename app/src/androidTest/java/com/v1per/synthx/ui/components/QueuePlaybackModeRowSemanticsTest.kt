package com.v1per.synthx.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Up Next Shuffle and Repeat buttons tell accessibility services their state: Shuffle is a
 * switch that is on or off, Repeat states Off, All or One. A touch on either still reaches its
 * handler, and so does the accessibility click.
 */
@RunWith(AndroidJUnit4::class)
class QueuePlaybackModeRowSemanticsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shuffle = mutableStateOf(false)
    private val repeat = mutableStateOf(RepeatMode.Off)
    private var shuffleClicks = 0
    private var repeatClicks = 0

    private fun setUp() {
        composeRule.setContent {
            QueuePlaybackModeRow(
                shuffleEnabled = shuffle.value,
                repeatMode = repeat.value,
                onShuffleClick = {
                    shuffleClicks++
                    shuffle.value = !shuffle.value
                },
                onRepeatClick = {
                    repeatClicks++
                    repeat.value = repeat.value.next()
                },
            )
        }
        composeRule.waitForIdle()
    }

    private fun shuffleButton(): SemanticsNodeInteraction =
        composeRule.onNode(hasContentDescription(context.getString(R.string.cd_shuffle)))

    private fun repeatButton(): SemanticsNodeInteraction =
        composeRule.onNode(hasContentDescription(context.getString(R.string.cd_repeat)))

    private fun SemanticsNodeInteraction.assertSwitch(state: ToggleableState) {
        assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))
    }

    private fun SemanticsNodeInteraction.assertStateDescription(res: Int) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(res)))

    @Test fun shuffleIsASwitchThatReportsOnAndOff() {
        setUp()
        shuffleButton().assertSwitch(ToggleableState.Off)

        shuffleButton().performClick()
        composeRule.waitForIdle()

        assertEquals("a touch reaches the handler", 1, shuffleClicks)
        shuffleButton().assertSwitch(ToggleableState.On)
    }

    @Test fun repeatStatesOffAllAndOne() {
        setUp()
        repeatButton().assertStateDescription(R.string.cd_repeat_off)

        repeatButton().performClick()
        composeRule.waitForIdle()
        repeatButton().assertStateDescription(R.string.cd_repeat_all)

        repeatButton().performClick()
        composeRule.waitForIdle()
        repeatButton().assertStateDescription(R.string.cd_repeat_one)

        assertEquals("touches reach the handler", 2, repeatClicks)
    }

    @Test fun theAccessibilityClickStillReachesBothHandlers() {
        setUp()
        val shuffleClick = shuffleButton().fetchSemanticsNode().config[SemanticsActions.OnClick].action
        val repeatClick = repeatButton().fetchSemanticsNode().config[SemanticsActions.OnClick].action

        composeRule.runOnUiThread {
            checkNotNull(shuffleClick).invoke()
            checkNotNull(repeatClick).invoke()
        }
        composeRule.waitForIdle()

        assertEquals(1, shuffleClicks)
        assertEquals(1, repeatClicks)
        shuffleButton().assertSwitch(ToggleableState.On)
        repeatButton().assertStateDescription(R.string.cd_repeat_all)
    }
}
