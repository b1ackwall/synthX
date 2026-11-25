package com.v1per.synthx.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.player.QueuePlaybackModeRowIntrinsicHeight
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Up Next Shuffle / Repeat row as laid out: exactly as tall as the constant the queue-mode art position is
 * computed from, with both toggles drawn as 48dp squares.
 */
@RunWith(AndroidJUnit4::class)
class QueuePlaybackModeRowLayoutInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theRowIsAsTallAsTheConstantTheArtPositionUses() {
        composeRule.setContent {
            SynthXTheme {
                QueuePlaybackModeRow(
                    shuffleEnabled = true,
                    repeatMode = RepeatMode.One,
                    onShuffleClick = {},
                    onRepeatClick = {},
                    modifier = Modifier.testTag(ROW),
                )
            }
        }
        composeRule.onNodeWithTag(ROW).assertHeightIsEqualTo(QueuePlaybackModeRowIntrinsicHeight)
        listOf(R.string.cd_shuffle, R.string.cd_repeat).forEach { description ->
            composeRule.onNode(hasContentDescription(context.getString(description)))
                .assertWidthIsEqualTo(48.dp)
                .assertHeightIsEqualTo(48.dp)
        }
    }

    private companion object {
        const val ROW = "mode_row"
    }
}
