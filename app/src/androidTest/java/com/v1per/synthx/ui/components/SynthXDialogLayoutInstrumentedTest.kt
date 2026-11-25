package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.components.SynthXButtonWidth.Dialog
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertPixelEventually
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SynthXAlertDialog's buttons as drawn in a real dialog window: a pair side by side or stacked at one width, a
 * three-button stack, a lone button, the role fills and label colours, and a pair that only stacks at 130% font
 * scale. Every arrangement is end-aligned to the dialog's 24dp content padding.
 */
@RunWith(AndroidJUnit4::class)
class SynthXDialogLayoutInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private fun dialog(width: Dp, confirm: @Composable () -> Unit, dismiss: (@Composable () -> Unit)? = null) {
        composeRule.setContent {
            SynthXTheme {
                SynthXAlertDialog(
                    onDismissRequest = {},
                    confirmButton = confirm,
                    modifier = Modifier.width(width).testTag(DIALOG),
                    dismissButton = dismiss,
                    title = { Text("Title") },
                    text = { Text("Body") },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun DpRect.widthDp(): Dp = right - left

    private fun assertNear(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, TOLERANCE_DP)

    /** The right edge of the dialog's content, inside its 24dp padding. */
    private fun contentEnd(): Dp = bounds(DIALOG).right - CONTENT_PADDING

    private fun labelColorOf(text: String): Color {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    private fun assertFill(tag: String, expected: Color) {
        val x = with(composeRule.density) { PIXEL_INSET.roundToPx() }
        composeRule.assertPixelEventually(
            description = "$tag fill",
            expected = expected,
            capture = { composeRule.onNodeWithTag(tag).captureToImage() },
        ) { image -> IntOffset(x, image.height / 2) }
    }

    @Composable
    private fun Primary(label: String, tag: String = PRIMARY) {
        SynthXPrimaryButton(onClick = {}, width = Dialog, modifier = Modifier.testTag(tag)) { Text(label) }
    }

    @Composable
    private fun Secondary(label: String, tag: String = SECONDARY) {
        SynthXSecondaryButton(onClick = {}, width = Dialog, modifier = Modifier.testTag(tag)) { Text(label) }
    }

    @Test fun aPairThatFitsSitsSideBySideEndAlignedAndDrawn8dpApart() {
        dialog(
            width = 280.dp,
            confirm = {
                SynthXDestructiveButton(onClick = {}, width = Dialog, modifier = Modifier.testTag(PRIMARY)) {
                    Text("Delete")
                }
            },
            dismiss = { Secondary("Cancel") },
        )
        val confirm = bounds(PRIMARY)
        val dismiss = bounds(SECONDARY)
        assertNear("same row", dismiss.top, confirm.top)
        assertNear("dismiss first, confirm 8dp after it", 8.dp, confirm.left - dismiss.right)
        assertNear("end-aligned", contentEnd(), confirm.right)
        composeRule.onNodeWithTag(PRIMARY).assertHeightIsEqualTo(40.dp).assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag(SECONDARY).assertHeightIsEqualTo(40.dp).assertTouchHeightIsEqualTo(48.dp)
    }

    @Test fun aPairThatDoesNotFitStacksConfirmOnTopAtOneWidthDrawn8dpApart() {
        dialog(width = 280.dp, confirm = { Primary("Turn off shuffle") }, dismiss = { Secondary("Keep shuffle on") })
        val confirm = bounds(PRIMARY)
        val dismiss = bounds(SECONDARY)
        assertTrue("confirm sits on top", confirm.bottom < dismiss.top)
        assertNear("visible gap between the stacked buttons", 8.dp, dismiss.top - confirm.bottom)
        assertNear("one shared width", confirm.widthDp(), dismiss.widthDp())
        assertNear("confirm end-aligned", contentEnd(), confirm.right)
        assertNear("dismiss end-aligned", contentEnd(), dismiss.right)
    }

    @Test fun aStackDrawsEveryButtonAtTheWidestLabelsWidthInOrder() {
        dialog(
            width = 280.dp,
            confirm = {
                SynthXDialogButtonStack(
                    buttons = listOf(
                        { Primary("Choose backup", tag = "first") },
                        { Secondary("Export diagnostics", tag = "second") },
                        { Secondary("Close", tag = "third") },
                    ),
                )
            },
        )
        val first = bounds("first")
        val second = bounds("second")
        val third = bounds("third")
        assertTrue("first on top", first.bottom < second.top && second.bottom < third.top)
        assertNear("gap below the first", 8.dp, second.top - first.bottom)
        assertNear("gap below the second", 8.dp, third.top - second.bottom)
        assertNear("first and second share one width", second.widthDp(), first.widthDp())
        assertNear("second and third share one width", second.widthDp(), third.widthDp())
        assertTrue("the shared width is the widest label's, not Close's", third.widthDp() > 120.dp)
        listOf(first, second, third).forEach { assertNear("end-aligned", contentEnd(), it.right) }
    }

    @Test fun aLoneButtonIsEndAligned() {
        dialog(width = 280.dp, confirm = { Primary("OK") })
        val ok = bounds(PRIMARY)
        assertNear("end-aligned", contentEnd(), ok.right)
        assertTrue("at least the 64dp minimum: ${ok.widthDp()}", ok.widthDp() >= 64.dp - TOLERANCE_DP.dp)
    }

    @Test fun eachRoleDrawsItsFillAndLabelColourOnTheSurfaceContainer() {
        dialog(
            width = 320.dp,
            confirm = {
                SynthXDialogButtonStack(
                    buttons = listOf(
                        { Primary("Keep") },
                        { Secondary("Clear") },
                        {
                            SynthXDestructiveButton(onClick = {
                            }, width = Dialog, modifier = Modifier.testTag(DESTRUCTIVE)) {
                                Text("Delete")
                            }
                        },
                    ),
                )
            },
        )
        assertFill(PRIMARY, TextPrimary)
        assertFill(SECONDARY, SurfaceVariant)
        assertFill(DESTRUCTIVE, Error)
        assertFill(DIALOG, Surface)
        assertEquals("Primary label", Background, labelColorOf("Keep"))
        assertEquals("Secondary label", TextPrimary, labelColorOf("Clear"))
        assertEquals("Destructive label", Background, labelColorOf("Delete"))
        listOf("Keep", "Clear", "Delete", "Title", "Body").forEach {
            assertNotEquals("'$it' paints Accent", Accent, labelColorOf(it))
        }
        assertEquals("title", TextPrimary, labelColorOf("Title"))
    }

    @Test fun atA130PercentFontScaleAPairThatFitsAt100PercentStacksAtOneWidth() {
        val fontScale = mutableFloatStateOf(1f)
        dialog(
            width = 300.dp,
            confirm = { Scaled(fontScale.floatValue) { Primary("Select folder") } },
            dismiss = { Scaled(fontScale.floatValue) { Secondary("Cancel") } },
        )
        assertNear("side by side at 100%", bounds(SECONDARY).top, bounds(PRIMARY).top)

        fontScale.floatValue = 1.3f
        composeRule.waitForIdle()
        val confirm = bounds(PRIMARY)
        val dismiss = bounds(SECONDARY)
        assertTrue("stacked at 130%, confirm on top", confirm.bottom < dismiss.top)
        assertNear("visible gap", 8.dp, dismiss.top - confirm.bottom)
        assertNear("one shared width", confirm.widthDp(), dismiss.widthDp())
        assertNear("end-aligned", contentEnd(), confirm.right)
    }

    /** Draws [content] at [fontScale], set inside the dialog because its window takes no font scale from its host. */
    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }

    private companion object {
        const val DIALOG = "dialog"
        const val PRIMARY = "primary"
        const val SECONDARY = "secondary"
        const val DESTRUCTIVE = "destructive"
        const val TOLERANCE_DP = 0.5f
        val CONTENT_PADDING = 24.dp
        val PIXEL_INSET = 6.dp
    }
}
