package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.ui.testing.assertPixelEventually
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The onboarding buttons as drawn on a phone-width screen: their token widths, the 48dp height,
 * stacking and centring, one-line labels at 100% font scale and the Primary or Secondary role each
 * one takes. Bounds are the drawn edges of each button.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingButtonsLayoutInstrumentedTest : OnboardingRecoveryTestBase() {

    /** Hosts the wizard in a screen exactly [width] wide at 100% font scale, whatever the window's own width. */
    private fun showWizard(width: Dp) {
        val factory = wizardFactory(library, backups, restore, onboarding)
        val startDestination = gatingStartDestination()
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1f)) {
                Box(
                    Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(width)
                        .fillMaxHeight()
                        .testTag(SCREEN_TAG),
                ) {
                    WizardContent(factory, startDestination)
                }
            }
        }
    }

    private fun label(id: Int) = context.getString(id)

    private fun bounds(text: String): DpRect = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot()

    private fun screen(): DpRect = composeRule.onNodeWithTag(SCREEN_TAG).getUnclippedBoundsInRoot()

    private fun assertNear(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, TOLERANCE_DP)

    private fun assertSize(text: String, width: Dp) {
        val button = bounds(text)
        assertNear("$text width", width, button.width)
        assertNear("$text height", 48.dp, button.height)
    }

    private fun assertCentred(text: String) {
        val button = bounds(text)
        val screen = screen()
        assertNear("$text centred", button.left - screen.left, screen.right - button.right)
    }

    // A label split onto two lines still fits the 48dp minimum height, so this reads the line count.
    private fun assertOneLine(text: String) {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("$text label lines", 1, layouts.single().lineCount)
    }

    /** Primary draws a Background label on a TextPrimary fill; Secondary a TextPrimary label on SurfaceVariant. */
    private fun assertLabelColour(text: String, primary: Boolean) =
        assertEquals("$text label colour", if (primary) Background else TextPrimary, textColorOf(text))

    // Sampled just inside the start edge, clear of the centred label. Re-captured until it shows: a capture
    // right after Retry disappears can still be the frame where this point was the gap below Retry.
    private fun assertTextPrimaryFill(text: String) {
        val x = with(composeRule.density) { PIXEL_INSET.roundToPx() }
        composeRule.assertPixelEventually(
            description = "$text fill",
            expected = TextPrimary,
            capture = { composeRule.onNodeWithText(text).captureToImage() },
        ) { image -> IntOffset(x, image.height / 2) }
    }

    @Test fun onA360dpScreenTheChoiceButtonsAreWideStackedCentredAndOnOneLine() {
        showWizard(360.dp)
        val fresh = label(R.string.onboarding_fresh_install)
        val recover = label(R.string.onboarding_recover_backup)

        listOf(fresh, recover).forEach {
            assertSize(it, 240.dp)
            assertCentred(it)
            assertOneLine(it)
        }
        val top = bounds(fresh)
        val bottom = bounds(recover)
        assertTrue("Fresh install sits above Recover from backup", top.bottom < bottom.top)
        assertNear("12dp between the stacked buttons", 12.dp, bottom.top - top.bottom)
        assertLabelColour(fresh, primary = true)
        assertLabelColour(recover, primary = false)
    }

    @Test fun onA700dpScreenTheChoiceButtonsSitSideBySideCentredAtTheirOwnWidth() {
        showWizard(700.dp)
        val fresh = bounds(label(R.string.onboarding_fresh_install))
        val recover = bounds(label(R.string.onboarding_recover_backup))
        val screen = screen()

        assertNear("same row", recover.top, fresh.top)
        assertNear("Fresh install width", 240.dp, fresh.width)
        assertNear("Recover from backup width", 240.dp, recover.width)
        assertNear("Recover from backup first, Fresh install 12dp after it", 12.dp, fresh.left - recover.right)
        assertNear("centred as a pair", recover.left - screen.left, screen.right - fresh.right)
    }

    /**
     * Done keeps 16dp between its bottom edge and the bottom of the screen and, while the
     * music-folder hint shows, at least 16dp between the hint and its top edge.
     */
    private fun assertDoneSpacing(hintShown: Boolean) {
        val done = bounds(label(R.string.action_done))
        assertNear("16dp between Done and the bottom of the screen", 16.dp, screen().bottom - done.bottom)
        val hint = composeRule.onNodeWithText(label(R.string.onboarding_music_folder_required_hint))
        if (hintShown) {
            val gap = done.top - hint.getUnclippedBoundsInRoot().bottom
            assertTrue("at least 16dp between the hint and Done, was $gap", gap >= 16.dp - TOLERANCE_DP.dp)
        } else {
            hint.assertDoesNotExist()
        }
    }

    @Test fun onA360dpScreenTheFolderStepDoneIsStandardCentredAndSpaced() {
        showWizard(360.dp)
        composeRule.onNodeWithText(label(R.string.onboarding_fresh_install)).performClick()
        assertDoneSpacing(hintShown = true)
        saveOnboardingMusic()
        val done = label(R.string.action_done)

        assertSize(done, 168.dp)
        assertCentred(done)
        assertOneLine(done)
        assertLabelColour(done, primary = true)
        assertDoneSpacing(hintShown = false)
    }

    @Test fun onA360dpScreenTheRecoveryFolderStepDoneIsStandardCentredAndSpaced() {
        showWizard(360.dp)
        composeRule.onNodeWithText(label(R.string.onboarding_recover_backup)).performClick()
        val done = label(R.string.action_done)
        composeRule.onNodeWithText(label(R.string.onboarding_recovery_folder_title)).assertExists()

        assertSize(done, 168.dp)
        assertCentred(done)
        assertOneLine(done)
        assertLabelColour(done, primary = true)
        assertDoneSpacing(hintShown = true)
    }

    @Test fun onA360dpScreenAFailedScanStacksRetryAboveChangeFolderCentred() {
        showFailedScan()
        val retry = label(R.string.action_retry)
        val changeFolder = label(R.string.onboarding_scan_change_folder)

        listOf(retry, changeFolder).forEach {
            assertSize(it, 168.dp)
            assertCentred(it)
            assertOneLine(it)
        }
        val top = bounds(retry)
        val bottom = bounds(changeFolder)
        assertTrue("Retry sits above Change folder", top.bottom < bottom.top)
        assertNear("12dp between the stacked buttons", 12.dp, bottom.top - top.bottom)
        assertLabelColour(retry, primary = true)
        assertLabelColour(changeFolder, primary = false)
    }

    /**
     * A revoked grant clears the stored folder, which the ViewModel mirrors after the failed scan.
     * No scan stub can fail after clearing it, so the test clears both copies itself.
     */
    @Test fun onA360dpScreenAFailedScanWithoutAFolderShowsChangeFolderAloneAsPrimary() {
        showFailedScan()
        composeRule.runOnUiThread {
            preferences.musicFolderUri = null
            library._musicFolderUri.value = null
        }
        composeRule.waitForIdle()
        val changeFolder = label(R.string.onboarding_scan_change_folder)

        composeRule.onNodeWithText(label(R.string.action_retry)).assertDoesNotExist()
        assertSize(changeFolder, 168.dp)
        assertCentred(changeFolder)
        assertOneLine(changeFolder)
        assertLabelColour(changeFolder, primary = true)
        assertTextPrimaryFill(changeFolder)
    }

    private fun showFailedScan() {
        ScanStubWorkerFactory.behavior = ScanStub.Fail("disk on fire")
        showWizard(360.dp)
        composeRule.onNodeWithText(label(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        composeRule.onNodeWithText(label(R.string.action_done)).performClick()
        awaitText(label(R.string.onboarding_scan_failed_title))
    }

    private companion object {
        const val SCREEN_TAG = "onboarding_screen"
        const val TOLERANCE_DP = 0.5f
        val PIXEL_INSET = 6.dp
    }
}
