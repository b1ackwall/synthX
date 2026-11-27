package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.ui.testing.over
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextSecondary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The welcome screen as drawn: centred, the text column capped and centred on wide windows, the
 * wordmark sized from the window width, scrolling with both buttons reachable and unclipped at a
 * 2.0 font scale in a short window, and the glow visible but quiet behind the wordmark.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingChoiceLayoutInstrumentedTest : OnboardingRecoveryTestBase() {

    /** Hosts the wizard in a [width] x [height] screen at [fontScale], whatever the window's own size. */
    private fun showChoice(width: Dp, height: Dp, fontScale: Float = 1f) {
        val factory = wizardFactory(library, backups, restore, onboarding)
        val startDestination = gatingStartDestination()
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(width, height)
                        .testTag(SCREEN_TAG),
                ) {
                    WizardContent(factory, startDestination)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun label(id: Int) = context.getString(id)

    private fun screen(): DpRect = composeRule.onNodeWithTag(SCREEN_TAG).getUnclippedBoundsInRoot()

    private fun heading(): SemanticsNodeInteraction = composeRule.onNode(isHeading())

    private fun bounds(text: String): DpRect = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot()

    private fun assertNear(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, TOLERANCE_DP)

    private fun assertInside(message: String, inner: DpRect, outer: DpRect) {
        assertTrue("$message: top ${inner.top} inside ${outer.top}", inner.top >= outer.top - TOLERANCE_DP.dp)
        assertTrue(
            "$message: bottom ${inner.bottom} inside ${outer.bottom}",
            inner.bottom <= outer.bottom + TOLERANCE_DP.dp,
        )
        assertTrue("$message: left ${inner.left} inside ${outer.left}", inner.left >= outer.left - TOLERANCE_DP.dp)
        assertTrue("$message: right ${inner.right} inside ${outer.right}", inner.right <= outer.right + TOLERANCE_DP.dp)
    }

    private fun assertCentredIn(message: String, inner: DpRect, outer: DpRect) =
        assertNear(message, inner.left - outer.left, outer.right - inner.right)

    private fun textLayout(text: String): TextLayoutResult {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /**
     * Every line is drawn whole: none ellipsized, none outside the width the text was given, no height
     * cut. The semantics layout positions centred lines in that full width while reporting the
     * narrower drawn size, so lines are checked against the width given, and hasVisualOverflow (which
     * compares the two) is not used.
     */
    private fun assertNotCutOff(text: String) {
        val layout = textLayout(text)
        val given = layout.layoutInput.constraints.maxWidth
        assertFalse("$text cut at the bottom", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            val span = "${layout.getLineLeft(line)}..${layout.getLineRight(line)}"
            val where = "$text line $line of ${layout.lineCount}, $span in $given"
            assertFalse("$where ellipsized", layout.isLineEllipsized(line))
            assertTrue("$where starts inside", layout.getLineLeft(line) >= -1f)
            assertTrue("$where ends inside", layout.getLineRight(line) <= given + 1f)
        }
    }

    @Test fun onAPhoneAtFontScale1EverythingFitsCentredWithoutScrolling() {
        showChoice(360.dp, 640.dp)
        val screen = screen()
        val wordmark = heading().getUnclippedBoundsInRoot()
        val subtitle = bounds(label(R.string.onboarding_welcome_subtitle))
        val recover = bounds(label(R.string.onboarding_recover_backup))

        assertNear("wordmark is 40% of the width", 144.dp, wordmark.width)
        assertCentredIn("wordmark centred", wordmark, screen)
        assertCentredIn("subtitle centred", subtitle, screen)
        listOf(wordmark, subtitle, bounds(label(R.string.onboarding_fresh_install)), recover).forEach {
            assertInside("on screen without scrolling", it, screen)
        }
        // Centred as a block: the space above the wordmark matches the space below the last button.
        assertNear("centred vertically", wordmark.top - screen.top, screen.bottom - recover.bottom)
    }

    @Test fun atFontScale2InAShortWindowBothButtonsScrollIntoViewUnclipped() {
        showChoice(360.dp, 400.dp, fontScale = 2f)
        val screen = screen()

        listOf(R.string.onboarding_fresh_install, R.string.onboarding_recover_backup).forEach { id ->
            val text = label(id)
            composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            val button = bounds(text)
            assertInside("$text reachable", button, screen)
            // The locked button keeps its fixed width and grows in height for a wrapped label.
            assertNear("$text keeps the Wide width", 240.dp, button.width)
            assertTrue("$text at least 48dp tall, was ${button.height}", button.height >= 48.dp - TOLERANCE_DP.dp)
            assertNotCutOff(text)
            assertInside(
                "$text label inside its button",
                composeRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot(),
                button,
            )
        }
        heading().performScrollTo().assertIsDisplayed()
        assertInside("heading reachable", heading().getUnclippedBoundsInRoot(), screen)
    }

    @Test fun atFontScale2OnAPhoneNothingIsCutOff() {
        showChoice(360.dp, 800.dp, fontScale = 2f)
        val subtitle = label(R.string.onboarding_welcome_subtitle)
        composeRule.onNodeWithText(subtitle).performScrollTo().assertIsDisplayed()
        assertNotCutOff(subtitle)
        listOf(R.string.onboarding_fresh_install, R.string.onboarding_recover_backup).forEach { id ->
            composeRule.onNodeWithText(label(id)).performScrollTo().assertIsDisplayed()
            assertNear("${label(id)} keeps the Wide width", 240.dp, bounds(label(id)).width)
        }
    }

    @Test fun onATabletWidthTheColumnIsCappedAndCentredAndThePairSitsSideBySide() {
        showChoice(840.dp, 1280.dp)
        val screen = screen()
        val wordmark = heading().getUnclippedBoundsInRoot()
        val subtitle = bounds(label(R.string.onboarding_welcome_subtitle))
        val fresh = bounds(label(R.string.onboarding_fresh_install))
        val recover = bounds(label(R.string.onboarding_recover_backup))

        assertNear("wordmark capped at 240dp", 240.dp, wordmark.width)
        assertCentredIn("wordmark centred", wordmark, screen)
        assertCentredIn("subtitle centred", subtitle, screen)
        assertTrue("subtitle within the 480dp column", subtitle.width <= 480.dp)
        assertNear("pair on one row", fresh.top, recover.top)
        assertNear("pair centred", recover.left - screen.left, screen.right - fresh.right)
    }

    /**
     * Just outside the wordmark's start edge the glow is about half strength: visibly above the
     * background, below the full-strength centre. A corner far from it is plain background.
     */
    @Test fun theGlowIsVisibleBesideTheWordmarkAndAbsentFarFromIt() {
        showChoice(360.dp, 640.dp)
        val wordmark = heading().getUnclippedBoundsInRoot()
        val image = composeRule.onNodeWithTag(SCREEN_TAG).captureToImage()
        val pixels = image.toPixelMap()
        val density = composeRule.density.density
        val beside = pixels[
            ((wordmark.left - SAMPLE_INSET).value * density).toInt(),
            ((wordmark.top + wordmark.bottom).value / 2 * density).toInt(),
        ]
        val corner = pixels[(SAMPLE_INSET.value * density).toInt(), (SAMPLE_INSET.value * density).toInt()]
        val peak = Accent.over(Background, Alpha.OnboardingGlow)

        assertTrue("glow visible beside the wordmark: $beside", beside.green - Background.green >= MIN_VISIBLE_STEP)
        assertTrue("glow quieter than its centre: $beside", beside.green < peak.green)
        assertTrue("corner is background: $corner", kotlin.math.abs(corner.green - Background.green) <= GRAIN_TOLERANCE)
    }

    /** The subtitle keeps AAA contrast even over the glow's full-strength centre. */
    @Test fun theSubtitleKeepsItsContrastOverTheBrightestGlow() {
        val peak = Accent.over(Background, Alpha.OnboardingGlow)
        val ratio = (TextSecondary.luminance() + 0.05f) / (peak.luminance() + 0.05f)
        assertTrue("TextSecondary over the glow peak: $ratio", ratio >= 7f)
    }

    private companion object {
        const val SCREEN_TAG = "onboarding_choice_screen"
        const val TOLERANCE_DP = 0.5f
        val SAMPLE_INSET = 4.dp

        /** Four 8-bit steps: clearly above the background, above the grain's one or two steps. */
        const val MIN_VISIBLE_STEP = 4f / 255f
        const val GRAIN_TOLERANCE = 3f / 255f
    }
}
