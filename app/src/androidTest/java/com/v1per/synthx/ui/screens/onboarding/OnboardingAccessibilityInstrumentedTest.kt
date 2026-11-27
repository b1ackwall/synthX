package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import org.junit.Test
import org.junit.runner.RunWith

/** Each wizard step's title is a heading; the scan status and the resume notice are polite live regions. */
@RunWith(AndroidJUnit4::class)
class OnboardingAccessibilityInstrumentedTest : OnboardingRecoveryTestBase() {

    private fun label(id: Int) = context.getString(id)

    private fun assertHeading(id: Int) {
        composeRule.onNode(hasText(label(id)) and isHeading()).assertExists()
    }

    private val politeLiveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test fun theWelcomeTitleIsTheChoiceHeadingInsteadOfTheWordmarkName() {
        startWizard()
        assertHeading(R.string.onboarding_welcome_title)
        // The wordmark's own "synthX" description is folded into the heading, not read twice.
        composeRule.onAllNodesWithContentDescription(label(R.string.cd_wordmark)).assertCountEquals(0)
    }

    @Test fun theFreshInstallFolderStepTitleIsAHeading() {
        startWizard()
        composeRule.onNodeWithText(label(R.string.onboarding_fresh_install)).performClick()
        assertHeading(R.string.onboarding_folder_setup_title)
    }

    @Test fun bothRecoveryStepTitlesAreHeadings() {
        startWizard()
        composeRule.onNodeWithText(label(R.string.onboarding_recover_backup)).performClick()
        assertHeading(R.string.onboarding_recovery_folder_title)
        composeRule.runOnUiThread { onboarding.goToRecoveryBackupSelect() }
        composeRule.waitForIdle()
        assertHeading(R.string.onboarding_recovery_backup_title)
    }

    @Test fun theScanningTitleIsAHeadingAndItsStatusAPoliteLiveRegion() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        startWizardAtDone()
        assertHeading(R.string.onboarding_scanning_title)
        val status = label(R.string.scan_progress_library)
        awaitText(status)
        composeRule.onNode(hasText(status) and politeLiveRegion).assertExists()
    }

    @Test fun theFailedScanTitleIsAHeading() {
        ScanStubWorkerFactory.behavior = ScanStub.Fail("disk on fire")
        startWizardAtDone()
        awaitText(label(R.string.onboarding_scan_failed_title))
        assertHeading(R.string.onboarding_scan_failed_title)
    }

    /** The notice shows for 2.5 s, so the clock is held while it is checked. */
    @Test fun theResumingRecoveryNoticeIsAPoliteLiveRegion() {
        startWizard()
        composeRule.onNodeWithText(label(R.string.onboarding_recover_backup)).performClick()
        composeRule.onNodeWithText(label(R.string.onboarding_recovery_folder_title)).assertExists()

        composeRule.mainClock.autoAdvance = false
        try {
            relaunchWizardAfterSimulatedProcessDeath()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNode(hasText(label(R.string.onboarding_resuming_recovery)) and politeLiveRegion)
                .assertExists()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }
}
