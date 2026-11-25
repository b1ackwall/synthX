package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertPixelEventually
import com.v1per.synthx.ui.testing.over
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.ButtonBorder
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * The app's snackbar as drawn: a SurfaceVariant box with the 1dp Secondary border inside a 12dp margin,
 * TextPrimary message and action, and an action tap that reaches the caller as ActionPerformed.
 */
@RunWith(AndroidJUnit4::class)
class SynthXSnackbarInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private var result: SnackbarResult? = null

    /** Shows one snackbar with a Retry action, through [SynthXSnackbarHost] or a tagged [SynthXSnackbar]. */
    private fun show(throughHost: Boolean) {
        composeRule.setContent {
            SynthXTheme {
                val state = remember { SnackbarHostState() }
                LaunchedEffect(state) {
                    result = state.showSnackbar(MESSAGE, actionLabel = ACTION, duration = SnackbarDuration.Indefinite)
                }
                Box(modifier = Modifier.fillMaxSize().background(Background)) {
                    if (throughHost) {
                        SynthXSnackbarHost(hostState = state, modifier = Modifier.align(Alignment.BottomCenter))
                    } else {
                        SnackbarHost(hostState = state, modifier = Modifier.align(Alignment.BottomCenter)) {
                            SynthXSnackbar(it, modifier = Modifier.testTag(SNACKBAR))
                        }
                    }
                }
            }
        }
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText(MESSAGE).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun labelColorOf(text: String): Color {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    @Test fun theBoxIsSurfaceVariantWithTheSecondaryBorderInsideA12dpMargin() {
        show(throughHost = false)
        val margin = with(composeRule.density) { MARGIN.toPx() }
        // The first whole pixel column inside the 1dp border, and one well inside the box.
        val borderX = ceil(margin).toInt() + 1
        val insideX = with(composeRule.density) { (MARGIN + 8.dp).roundToPx() }
        val capture = { composeRule.onNodeWithTag(SNACKBAR).captureToImage() }
        composeRule.assertPixelEventually("container", SurfaceVariant, capture) { IntOffset(insideX, it.height / 2) }
        composeRule.assertPixelEventually("border", TextPrimary.over(SurfaceVariant, ButtonBorder.alpha), capture) {
            IntOffset(borderX, it.height / 2)
        }
    }

    @Test fun messageAndActionAreTextPrimaryAndNeverAccent() {
        show(throughHost = true)
        listOf(MESSAGE, ACTION).forEach { text ->
            val color = labelColorOf(text)
            assertNotEquals("'$text' paints Accent", Accent, color)
            assertEquals("'$text' colour", TextPrimary, color)
        }
    }

    @Test fun aTapOnTheActionReachesTheCallerAsActionPerformed() {
        show(throughHost = true)
        composeRule.onNodeWithText(ACTION).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { result != null }
        assertEquals(SnackbarResult.ActionPerformed, result)
    }

    private companion object {
        const val SNACKBAR = "snackbar"
        const val MESSAGE = "Song deleted, but its file is still on the device"
        const val ACTION = "Retry"
        const val TIMEOUT_MS = 5_000L
        val MARGIN = 12.dp
    }
}
