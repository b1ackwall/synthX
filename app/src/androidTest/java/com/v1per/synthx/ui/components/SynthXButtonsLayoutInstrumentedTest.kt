package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.components.SynthXButtonWidth.Compact
import com.v1per.synthx.ui.components.SynthXButtonWidth.Dialog
import com.v1per.synthx.ui.components.SynthXButtonWidth.Standard
import com.v1per.synthx.ui.components.SynthXButtonWidth.Wide
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared buttons and SynthXButtonPair as drawn: sizes, tap areas, wrapping, pair gaps and
 * arrangement, and the disabled state. Visual bounds are the button's drawn edges
 * (getUnclippedBoundsInRoot); touch bounds include the 48dp minimum tap area.
 */
@RunWith(AndroidJUnit4::class)
class SynthXButtonsLayoutInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    /** Hosts [content] in a column exactly [width] wide at the window's start, whatever the window's own width. */
    private fun show(width: Dp, content: @Composable ColumnScope.() -> Unit) {
        composeRule.setContent {
            SynthXTheme {
                Column(
                    modifier = Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(width)
                        .background(Background)
                        .testTag(CONTAINER),
                    content = content,
                )
            }
        }
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun DpRect.widthDp(): Dp = right - left

    private fun DpRect.heightDp(): Dp = bottom - top

    private fun assertNear(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, TOLERANCE_DP)

    @Composable
    private fun TaggedPair(
        primary: SynthXButtonWidth,
        primaryLabel: String,
        secondary: SynthXButtonWidth,
        secondaryLabel: String,
    ) {
        SynthXButtonPair(
            primary = {
                SynthXPrimaryButton(onClick = {}, width = primary, modifier = Modifier.testTag(PRIMARY)) {
                    Text(primaryLabel)
                }
            },
            secondary = {
                SynthXSecondaryButton(onClick = {}, width = secondary, modifier = Modifier.testTag(SECONDARY)) {
                    Text(secondaryLabel)
                }
            },
        )
    }

    @Test fun screenTokensDrawAtTheirFixedWidthsAnd48dpTall() {
        show(360.dp) {
            SynthXPrimaryButton(onClick = {}, width = Compact, modifier = Modifier.testTag("compact")) { Text("Play") }
            SynthXSecondaryButton(onClick = {}, width = Standard, modifier = Modifier.testTag("standard")) {
                Text("Shuffle")
            }
            SynthXPrimaryButton(onClick = {
            }, width = Wide, modifier = Modifier.testTag("wide")) { Text("Fresh install") }
        }
        listOf("compact" to 112.dp, "standard" to 168.dp, "wide" to 240.dp).forEach { (tag, width) ->
            composeRule.onNodeWithTag(tag)
                .assertWidthIsEqualTo(width)
                .assertHeightIsEqualTo(48.dp)
                .assertTouchHeightIsEqualTo(48.dp)
        }
    }

    @Test fun aDialogButtonIs40dpTallWithA48dpTapAreaAndFollowsItsLabel() {
        show(360.dp) {
            SynthXPrimaryButton(onClick = {}, width = Dialog, modifier = Modifier.testTag("short")) { Text("X") }
            SynthXPrimaryButton(onClick = {}, width = Dialog, modifier = Modifier.testTag("long")) {
                Text("Keep shuffle on")
            }
        }
        // "X" plus 2 x 24dp of padding is narrower than 64dp, so the floor applies.
        composeRule.onNodeWithTag("short")
            .assertWidthIsEqualTo(64.dp)
            .assertHeightIsEqualTo(40.dp)
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("long").assertHeightIsEqualTo(40.dp).assertTouchHeightIsEqualTo(48.dp)
        val shortWidth = bounds("short").widthDp()
        val longWidth = bounds("long").widthDp()
        assertTrue("a longer label widens a Dialog button: $longWidth vs $shortWidth", longWidth > shortWidth + 1.dp)
    }

    @Test fun aLabelLongerThanAFixedWidthWrapsAndTheButtonGrowsTaller() {
        show(360.dp) {
            SynthXSecondaryButton(onClick = {}, width = Compact, modifier = Modifier.testTag("wrapped")) {
                Text("Recover from your backup")
            }
        }
        composeRule.onNodeWithTag("wrapped").assertWidthIsEqualTo(112.dp)
        val height = bounds("wrapped").heightDp()
        assertTrue("a wrapped label makes the button taller than 48dp: $height", height > 48.dp + TOLERANCE_DP.dp)
    }

    @Test fun aStackedDialogPairDraws8dpApartAtTheWiderLabelsWidth() {
        show(200.dp) {
            SynthXPrimaryButton(onClick = {}, width = Dialog, modifier = Modifier.testTag("alone primary")) {
                Text(TURN_OFF)
            }
            SynthXSecondaryButton(onClick = {}, width = Dialog, modifier = Modifier.testTag("alone secondary")) {
                Text(KEEP_ON)
            }
            TaggedPair(Dialog, TURN_OFF, Dialog, KEEP_ON)
        }
        val primary = bounds(PRIMARY)
        val secondary = bounds(SECONDARY)
        val wider = maxOf(bounds("alone primary").widthDp(), bounds("alone secondary").widthDp())
        assertTrue("the primary button is on top", primary.bottom < secondary.top)
        assertNear("visible gap between the stacked buttons", 8.dp, secondary.top - primary.bottom)
        assertNear("primary width", wider, primary.widthDp())
        assertNear("secondary width", wider, secondary.widthDp())
        assertTrue("the shared width is at least 64dp", wider >= 64.dp)
        composeRule.onNodeWithTag(PRIMARY).assertHeightIsEqualTo(40.dp)
        composeRule.onNodeWithTag(SECONDARY).assertHeightIsEqualTo(40.dp)
    }

    @Test fun aSideBySideDialogPairDraws8dpApart() {
        show(360.dp) { TaggedPair(Dialog, "Delete", Dialog, "Cancel") }
        val primary = bounds(PRIMARY)
        val secondary = bounds(SECONDARY)
        assertNear("same row", secondary.top, primary.top)
        assertNear("gap between the side-by-side buttons", 8.dp, primary.left - secondary.right)
    }

    @Test fun twoCompactButtonsSitSideBySideAndCentredAt360dp() {
        show(360.dp) { TaggedPair(Compact, "Play", Compact, "Shuffle") }
        val container = bounds(CONTAINER)
        val primary = bounds(PRIMARY)
        val secondary = bounds(SECONDARY)
        assertNear("same row", secondary.top, primary.top)
        assertNear("gap", 12.dp, primary.left - secondary.right)
        assertNear("primary width", 112.dp, primary.widthDp())
        assertNear("secondary width", 112.dp, secondary.widthDp())
        assertNear("centred", secondary.left - container.left, container.right - primary.right)
    }

    @Test fun aStandardAndWidePairStacksAt360dpAtTheWiderWidth12dpApart() {
        show(360.dp) { TaggedPair(Standard, "Fresh install", Wide, "Recover from backup") }
        val primary = bounds(PRIMARY)
        val secondary = bounds(SECONDARY)
        assertTrue("the primary button is on top", primary.bottom < secondary.top)
        assertNear("gap", 12.dp, secondary.top - primary.bottom)
        assertNear("primary takes the wider token", 240.dp, primary.widthDp())
        assertNear("secondary width", 240.dp, secondary.widthDp())
    }

    @Test fun aStandardAndWidePairSitsSideBySideAt700dpAtItsOwnWidths() {
        show(700.dp) { TaggedPair(Standard, "Fresh install", Wide, "Recover from backup") }
        val primary = bounds(PRIMARY)
        val secondary = bounds(SECONDARY)
        assertNear("same row", secondary.top, primary.top)
        assertNear("gap", 12.dp, primary.left - secondary.right)
        assertNear("primary keeps Standard", 168.dp, primary.widthDp())
        assertNear("secondary keeps Wide", 240.dp, secondary.widthDp())
    }

    @Test fun aDisabledButtonDrawsAt38PercentAndIgnoresClicks() {
        var enabledClicks = 0
        var disabledClicks = 0
        show(360.dp) {
            SynthXPrimaryButton(onClick = {
                enabledClicks++
            }, width = Compact, modifier = Modifier.testTag("enabled")) {
                Text("Play")
            }
            SynthXPrimaryButton(
                onClick = { disabledClicks++ },
                width = Compact,
                modifier = Modifier.testTag("disabled"),
                enabled = false,
            ) { Text("Play") }
        }
        // Sampled before any click, so no ripple is drawn.
        assertContainerPixel("enabled", TextPrimary, alpha = 1f)
        assertContainerPixel("disabled", TextPrimary, alpha = Alpha.Disabled)

        composeRule.onNodeWithTag("enabled").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("disabled").assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals("the enabled button takes the click", 1, enabledClicks)
        assertEquals("the disabled button ignores the click", 0, disabledClicks)
    }

    /**
     * Reads a pixel of [tag]'s container just inside its start edge, clear of the centred label, and
     * compares it with [color] drawn at [alpha] over Background.
     */
    private fun assertContainerPixel(tag: String, color: Color, alpha: Float) {
        val image = composeRule.onNodeWithTag(tag).captureToImage()
        val x = with(composeRule.density) { PIXEL_INSET.roundToPx() }
        val actual = image.toPixelMap()[x, image.height / 2]
        fun over(top: Float, bottom: Float) = top * alpha + bottom * (1f - alpha)
        assertEquals("$tag red", over(color.red, Background.red), actual.red, COLOR_TOLERANCE)
        assertEquals("$tag green", over(color.green, Background.green), actual.green, COLOR_TOLERANCE)
        assertEquals("$tag blue", over(color.blue, Background.blue), actual.blue, COLOR_TOLERANCE)
    }

    private companion object {
        const val CONTAINER = "container"
        const val PRIMARY = "primary"
        const val SECONDARY = "secondary"
        const val TURN_OFF = "Turn off shuffle"
        const val KEEP_ON = "Keep shuffle on"
        const val TOLERANCE_DP = 0.5f
        const val COLOR_TOLERANCE = 3f / 255f
        val PIXEL_INSET = 6.dp
    }
}
