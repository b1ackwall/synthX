package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.player.LocalPlayerContentColors
import com.v1per.synthx.ui.player.PlayerContentColors
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertImageEventually
import com.v1per.synthx.ui.testing.assertPixelEventually
import com.v1per.synthx.ui.testing.colorsMatch
import com.v1per.synthx.ui.testing.over
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player toggle as drawn over artwork-like backdrops: a 48dp square that is filled in the content colour
 * with the icon in the other colour when on, an unfilled square with the icon at full strength when off, the
 * disabled alpha only when unavailable, and switch or stated-button semantics.
 */
@RunWith(AndroidJUnit4::class)
class PlayerToggleButtonInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val checked = mutableStateOf(false)
    private var clicks = 0

    /** [colors] over [backdrop], the way the player sheet draws its controls over artwork. */
    private fun show(
        colors: PlayerContentColors,
        backdrop: Color,
        stateDescription: String? = null,
        available: Boolean = true,
        interactive: Boolean = true,
    ) {
        composeRule.setContent {
            SynthXTheme {
                CompositionLocalProvider(LocalPlayerContentColors provides colors) {
                    Box(modifier = Modifier.background(backdrop).padding(16.dp)) {
                        PlayerToggleButton(
                            checked = checked.value,
                            onClick = {
                                clicks++
                                checked.value = !checked.value
                            },
                            icon = Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.testTag(TOGGLE),
                            stateDescription = stateDescription,
                            available = available,
                            interactive = interactive,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val capture: () -> ImageBitmap = { composeRule.onNodeWithTag(TOGGLE).captureToImage() }

    /** A pixel inside the square's corner, clear of the 24dp icon in its middle. */
    private fun assertCorner(expected: Color, description: String) {
        val inset = with(composeRule.density) { 4.dp.roundToPx() }
        composeRule.assertPixelEventually(description, expected, capture) { IntOffset(inset, inset) }
    }

    /** The icon's glyph is drawn in [expected]: many pixels of its middle 24dp match it exactly. */
    private fun assertIcon(expected: Color, description: String) {
        composeRule.assertImageEventually(description, capture) { image ->
            val map = image.toPixelMap()
            val iconPixels = with(composeRule.density) { 24.dp.roundToPx() }
            val start = (map.width - iconPixels) / 2
            var matching = 0
            for (y in start until start + iconPixels) {
                for (x in start until start + iconPixels) {
                    if (colorsMatch(expected, map[x, y])) matching++
                }
            }
            if (matching >= MIN_GLYPH_PIXELS) null else "only $matching glyph pixels match $expected"
        }
    }

    @Test fun theToggleIsA48dpSquareTappedAtItsOwnSize() {
        show(PlayerContentColors.Light, Background)
        composeRule.onNodeWithTag(TOGGLE)
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
    }

    @Test fun overDarkArtworkOnIsAFilledSquareAndOffIsTheIconAtFullStrength() {
        show(PlayerContentColors.Light, Background)
        assertCorner(Background, "off: no fill")
        assertIcon(TextPrimary, "off: icon in the full content colour")

        composeRule.onNodeWithTag(TOGGLE).performClick()
        composeRule.waitForIdle()
        assertCorner(TextPrimary, "on: filled in the content colour")
        assertIcon(Background, "on: icon in onContent")
    }

    @Test fun overBrightArtworkTheColoursSwapWithTheContentColour() {
        show(PlayerContentColors.Dark, TextPrimary)
        assertCorner(TextPrimary, "off: no fill")
        assertIcon(Background, "off: icon in the full content colour")

        composeRule.onNodeWithTag(TOGGLE).performClick()
        composeRule.waitForIdle()
        assertCorner(Background, "on: filled in the content colour")
        assertIcon(TextPrimary, "on: icon in onContent")
    }

    @Test fun anUnavailableToggleDrawsAt38PercentAndIgnoresTaps() {
        checked.value = true
        show(PlayerContentColors.Light, Background, available = false)
        assertCorner(TextPrimary.over(Background, Alpha.Disabled), "unavailable fill at 38%")
        composeRule.onNodeWithTag(TOGGLE).assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals("an unavailable toggle ignores taps", 0, clicks)
    }

    @Test fun aNonInteractiveToggleKeepsItsFullStrengthAndIgnoresTaps() {
        checked.value = true
        show(PlayerContentColors.Light, Background, interactive = false)
        assertCorner(TextPrimary, "the click gate never dims the fill")
        assertIcon(Background, "the click gate never dims the icon")
        composeRule.onNodeWithTag(TOGGLE).assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals("a non-interactive toggle ignores taps", 0, clicks)
    }

    @Test fun withoutAStateDescriptionItIsASwitchThatReportsOnAndOff() {
        show(PlayerContentColors.Light, Background)
        val toggle = composeRule.onNodeWithTag(TOGGLE)
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        toggle.performClick()
        composeRule.waitForIdle()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        assertEquals(1, clicks)
    }

    @Test fun withAStateDescriptionItIsAButtonThatStatesIt() {
        show(PlayerContentColors.Light, Background, stateDescription = "All")
        val toggle = composeRule.onNodeWithTag(TOGGLE)
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "All"))
        toggle.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        toggle.performClick()
        composeRule.waitForIdle()
        assertEquals(1, clicks)
    }

    private companion object {
        const val TOGGLE = "toggle"

        // The shuffle glyph covers far more than this at any density; a dimmed glyph matches none.
        const val MIN_GLYPH_PIXELS = 40
    }
}
