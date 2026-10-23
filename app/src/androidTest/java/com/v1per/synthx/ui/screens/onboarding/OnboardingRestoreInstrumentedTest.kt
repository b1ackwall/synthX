package com.v1per.synthx.ui.screens.onboarding

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.screens.restore.RestoreOutcome
import com.v1per.synthx.ui.screens.restore.RestorePreview
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Recovery restore through the wizard: the restore gate, the confirm step and resume after process death. */
@RunWith(AndroidJUnit4::class)
class OnboardingRestoreInstrumentedTest : OnboardingRecoveryTestBase() {

    @Test fun restoreGateReadsChangesMadeThroughAnotherPreferencesInstance() {
        val elsewhere = PreferencesManager(context)
        assertFalse(backups.musicFolderConfigured)
        elsewhere.musicFolderUri = music.toString()
        assertTrue(backups.musicFolderConfigured)
        elsewhere.musicFolderUri = null
        assertFalse(backups.musicFolderConfigured)
    }

    @Test fun missingMusicFolderDoesNotCompleteOnboardingOrSubmitRestore() {
        var completed = false
        val before = workManager.getWorkInfosForUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).get()
        composeRule.runOnUiThread {
            restore.restore(backup(Uri.EMPTY)) { completed = true }
        }
        await(restore.outcome) { it is RestoreOutcome.Failed }
        assertFalse(completed)
        assertEquals(
            before.map { it.id },
            workManager.getWorkInfosForUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).get().map { it.id },
        )
    }

    @Test fun recoveryStaysInWizardAndNewArrivalsCanBeSkippedWithBothBackActions() {
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recover_backup)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsNotEnabled()
        saveMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled().performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertExists()
        composeRule.runOnIdle { assertEquals(Screen.Onboarding.route, navController.currentDestination?.route) }
        assertNull(preferences.newArrivalsFolderUri)

        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_folder_title)).assertExists()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_welcome_title)).assertExists()
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
    }

    @Test fun failedRestoreRunsAndStaysInWizardWithWorkerDiagnostic() {
        val (candidate, source) = validCandidateWithMetadata()
        saveMusic()
        startWizard()
        composeRule.runOnUiThread { onboarding.goToRecoveryBackupSelect() }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertExists()
        composeRule.runOnUiThread { restore.beginPreview(candidate) }
        await(restore.preview) { it is RestorePreview.Ready || it is RestorePreview.Failed }
        assertTrue(restore.preview.value is RestorePreview.Ready)
        // Metadata was complete and matched during preview; mutate bytes afterward so the worker
        // reaches RestoreTransaction's source-size/SHA validation and fails before exclusive.
        RandomAccessFile(source, "rw").use { file ->
            file.seek(file.length() - 1L)
            val original = file.readByte().toInt()
            file.seek(file.length() - 1L)
            file.writeByte(original xor 0x01)
        }
        confirmRestoreWithTypedWord()
        val request = awaitRequest()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        await(restore.outcome) { it is RestoreOutcome.Failed }

        composeRule.onNodeWithText(context.getString(R.string.restore_failed_title)).assertExists()
        composeRule.onNodeWithText("Home after onboarding").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(Screen.Onboarding.route, navController.currentDestination?.route)
        }
        assertTrue((restore.outcome.value as RestoreOutcome.Failed).message.contains("verified size and SHA-256"))
        composeRule.onNodeWithText(context.getString(R.string.action_ok)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_folder_title)).assertExists()
    }

    @Test fun submittedRestoreNavigatesOnlyAfterRestorePreparing() {
        val (candidate) = validCandidateWithMetadata()
        saveMusic()
        startWizard()
        composeRule.runOnUiThread { onboarding.goToRecoveryBackupSelect() }
        composeRule.runOnUiThread { restore.beginPreview(candidate) }
        await(restore.preview) { it is RestorePreview.Ready }
        confirmRestoreWithTypedWord()
        awaitRequest()

        composeRule.onNodeWithText("Home after onboarding").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(Screen.Onboarding.route, navController.currentDestination?.route)
        }

        runBlocking { RestoreCoordinator.exclusive { } }
        composeRule.onNodeWithText("Home after onboarding").assertExists()
        composeRule.runOnIdle {
            assertEquals(Screen.Home.route, navController.currentDestination?.route)
            assertNull(navController.previousBackStackEntry)
        }
    }

    /** Acceptance test: process death mid-recovery resumes the wizard, not Home. */
    @Test fun processDeathMidRecoveryResumesWizardNotHome() {
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recover_backup)).performClick()
        saveMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled().performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertExists()
        // Ground truth: recovery's folder pick alone must NOT have satisfied completion.
        assertFalse(preferences.onboardingComplete)
        assertEquals(OnboardingStep.RecoveryBackupSelect.name, preferences.onboardingStepName)

        relaunchWizardAfterSimulatedProcessDeath()

        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertExists()
        composeRule.onNodeWithText("Home after onboarding").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(Screen.Onboarding.route, navController.currentDestination?.route)
        }
    }

    @Test fun pickingRecoveryFolderAloneDoesNotSetOnboardingComplete() {
        saveMusic()
        composeRule.runOnIdle { }
        assertFalse(preferences.onboardingComplete)
    }
}
