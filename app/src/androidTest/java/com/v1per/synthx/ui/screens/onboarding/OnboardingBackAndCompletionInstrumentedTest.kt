package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.screens.restore.RestorePreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System Back inside the wizard, and that no completed wizard stays reachable: the steps share one
 * nav route, so each step's own BackHandler is all that keeps Back from leaving the app, and each
 * completion path must pop the route.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingBackAndCompletionInstrumentedTest : OnboardingRecoveryTestBase() {

    private fun label(id: Int) = context.getString(id)

    /** Whether anything in the app would take a system Back; false means it goes to the system and leaves the app. */
    private fun appHandlesBack(): Boolean {
        composeRule.waitForIdle()
        var enabled = false
        composeRule.runOnUiThread { enabled = composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** Home is the only destination left: the wizard route is gone and nothing in the app takes Back. */
    private fun assertOnboardingUnreachableFromHome() {
        composeRule.onNodeWithText(HOME_TEXT).assertExists()
        composeRule.runOnIdle {
            assertEquals(Screen.Home.route, navController.currentDestination?.route)
            assertNull("nothing under Home", navController.previousBackStackEntry)
            assertTrue(
                "the wizard route is off the back stack",
                runCatching { navController.getBackStackEntry(Screen.Onboarding.route) }.isFailure,
            )
        }
        assertFalse("system Back from Home leaves the app", appHandlesBack())
        composeRule.onNodeWithText(label(R.string.onboarding_welcome_title)).assertDoesNotExist()
    }

    @Test fun systemBackOnTheFreshInstallFolderStepReturnsToChoice() {
        startWizard()
        assertFalse("Back on Choice still leaves the app", appHandlesBack())
        composeRule.onNodeWithText(label(R.string.onboarding_fresh_install)).performClick()
        composeRule.onNodeWithText(label(R.string.onboarding_folder_setup_title)).assertExists()
        assertTrue("the folder step takes Back", appHandlesBack())
        saveOnboardingMusic()

        pressBack()

        composeRule.onNodeWithText(label(R.string.onboarding_welcome_title)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.onboarding_folder_setup_title)).assertDoesNotExist()
        assertEquals(OnboardingStep.Choice.name, preferences.onboardingStepName)
        assertFalse("Back on Choice leaves the app again", appHandlesBack())
        // Going back starts and loses nothing: the folder stays, no scan, onboarding not complete.
        assertEquals(music, library.musicFolderUri.value)
        assertTrue(scanIds().isEmpty())
        assertFalse(preferences.onboardingComplete)
    }

    @Test fun changeFolderAfterAFailedScanThenSystemBackReturnsToChoice() {
        ScanStubWorkerFactory.behavior = ScanStub.Fail("disk on fire")
        startWizardAtDone()
        awaitText(label(R.string.onboarding_scan_failed_title))
        composeRule.onNodeWithText(label(R.string.onboarding_scan_change_folder)).performClick()
        composeRule.onNodeWithText(label(R.string.onboarding_folder_setup_title)).assertExists()
        assertFalse(preferences.onboardingComplete)

        pressBack()

        composeRule.onNodeWithText(label(R.string.onboarding_welcome_title)).assertIsDisplayed()
        assertEquals(OnboardingStep.Choice.name, preferences.onboardingStepName)
    }

    @Test fun afterFreshInstallCompletesSystemBackFromHomeCannotReachOnboarding() {
        startWizardAtDone()
        awaitText(HOME_TEXT)

        assertOnboardingUnreachableFromHome()
        assertTrue(preferences.onboardingComplete)
        assertNull(preferences.firstScanRequestId)
    }

    @Test fun afterRecoveryCompletesSystemBackFromHomeCannotReachOnboarding() {
        val (candidate) = validCandidateWithMetadata()
        saveMusic()
        startWizard()
        composeRule.runOnUiThread { onboarding.goToRecoveryBackupSelect() }
        composeRule.runOnUiThread { restore.beginPreview(candidate) }
        await(restore.preview) { it is RestorePreview.Ready }
        confirmRestoreWithTypedWord()
        awaitRequest()
        // The handover waits for RESTORE_PREPARING, which the exclusive section stands in for.
        runBlocking { RestoreCoordinator.exclusive { } }
        awaitText(HOME_TEXT)

        assertOnboardingUnreachableFromHome()
    }

    @Test fun theFolderStepSaysTheScanStartsAtDone() {
        startWizard()
        composeRule.onNodeWithText(label(R.string.onboarding_fresh_install)).performClick()
        val hint = composeRule.onNodeWithText(label(R.string.onboarding_folder_setup_scan_hint))
        hint.assertIsDisplayed()

        // One line under the description, above the folder rows.
        val description = composeRule.onNodeWithText(label(R.string.onboarding_folder_setup_subtitle))
            .getUnclippedBoundsInRoot()
        val musicRow = composeRule.onNodeWithText(label(R.string.settings_music_folder)).getUnclippedBoundsInRoot()
        val hintBounds = hint.getUnclippedBoundsInRoot()
        assertTrue("hint below the description", hintBounds.top >= description.bottom)
        assertTrue("hint above the music folder row", hintBounds.bottom <= musicRow.top)
    }

    private companion object {
        const val HOME_TEXT = "Home after onboarding"
    }
}
