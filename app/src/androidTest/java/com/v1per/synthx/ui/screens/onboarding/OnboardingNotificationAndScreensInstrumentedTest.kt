package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.data.backup.pruneFinishedWorkIfRestoreAcknowledged
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.ui.screens.restore.RestorePreview
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Notification rationale, the deferred-scan message and what the real wizard screens show. */
@RunWith(AndroidJUnit4::class)
class OnboardingNotificationAndScreensInstrumentedTest : OnboardingRecoveryTestBase() {

    // ------------------------------------------------------------------ notification rationale

    /** Nothing is asked over the Welcome screen — the ask arrives only with Done. */
    @Test fun notificationRationaleAppearsAtDoneNotOverWelcomeAndHoldsTheScanUntilContinue() {
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_welcome_title)).assertExists()
        composeRule.onNodeWithText(rationaleTitle()).assertDoesNotExist()
        assertFalse(preferences.notificationPermissionAsked)

        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        composeRule.onNodeWithText(rationaleTitle()).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()

        composeRule.onNodeWithText(rationaleTitle()).assertExists()
        assertTrue("no scan may start before the user continues", scanIds().isEmpty())
        assertFalse(preferences.onboardingComplete)
        assertFalse(preferences.notificationPermissionAsked)
        assertEquals("the system prompt is never launched over Welcome or before Continue", 0, systemPromptLaunches)
    }

    @Test fun notificationRationaleContinueStartsTheFirstScanAndRecordsTheAsk() {
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        assertEquals(0, systemPromptLaunches)
        composeRule.onNodeWithText(context.getString(R.string.action_continue)).performClick()
        // The scan proceeds (and the wizard hands over) although the fake prompt never answers.
        composeRule.waitUntil(10_000) { scanIds().isNotEmpty() }
        composeRule.onNodeWithText("Home after onboarding").assertExists()
        assertEquals(1, systemPromptLaunches)
        assertTrue(preferences.notificationPermissionAsked)
        composeRule.onNodeWithText(rationaleTitle()).assertDoesNotExist()
    }

    /** A second Done (e.g. after Change folder) must not re-ask. */
    @Test fun notificationRationaleDoesNotReappearOnceAsked() {
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        check(preferences.markNotificationPermissionAskedDurably())
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()

        composeRule.onNodeWithText(rationaleTitle()).assertDoesNotExist()
        composeRule.waitUntil(10_000) { scanIds().isNotEmpty() }
        composeRule.onNodeWithText("Home after onboarding").assertExists()
        assertEquals("already asked: no second system prompt", 0, systemPromptLaunches)
    }

    /** The recovery restore is the other early trigger — the ask comes before the enqueue. */
    @Test fun notificationRationaleAppearsAtRecoveryRestoreConfirmBeforeTheRestoreIsEnqueued() {
        val (candidate) = validCandidateWithMetadata()
        saveMusic()
        startWizard()
        composeRule.runOnUiThread { onboarding.goToRecoveryBackupSelect() }
        composeRule.runOnUiThread { restore.beginPreview(candidate) }
        await(restore.preview) { it is RestorePreview.Ready }
        composeRule.onNodeWithText(rationaleTitle()).assertDoesNotExist()
        confirmRestoreWithTypedWord()

        composeRule.onNodeWithText(rationaleTitle()).assertExists()
        assertTrue(
            "the restore must not be enqueued before the user continues",
            workManager.getWorkInfosForUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).get().isEmpty(),
        )
        assertFalse(preferences.notificationPermissionAsked)

        assertEquals(0, systemPromptLaunches)

        composeRule.onNodeWithText(context.getString(R.string.action_continue)).performClick()
        awaitRequest()
        assertEquals(1, systemPromptLaunches)
        assertTrue(preferences.notificationPermissionAsked)
        composeRule.onNodeWithText(rationaleTitle()).assertDoesNotExist()
    }

    @Test fun pendingFirstScanHoldsBackLaunchMaintenanceAndFinishedWorkPruning() {
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        saveOnboardingMusic()
        composeRule.runOnUiThread { library.startFirstScan() }
        // Complete flag is on, but the wizard has not handed over yet.
        assertTrue(preferences.onboardingComplete)
        assertFalse(com.v1per.synthx.shouldRunLaunchMaintenance(preferences))
        assertNull(runBlocking { pruneFinishedWorkIfRestoreAcknowledged(workManager, preferences) })
        // The finished record the resumed ScanningStep needs is therefore still there.
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(scanIds().single()).get()?.state)

        composeRule.runOnUiThread { library.completeFirstScan() }
        assertTrue(com.v1per.synthx.shouldRunLaunchMaintenance(preferences))
        assertNotNull(runBlocking { pruneFinishedWorkIfRestoreAcknowledged(workManager, preferences) })
    }

    // --- the deferred-scan message is informational and clears with its scan ------

    @Test fun deferredScanMessageIsInformationalNotAnErrorAndClearsWhenItsScanStopsWaiting() {
        saveMusic()
        val hot = keepNoticeHot()
        try {
            val gate = CompletableDeferred<Unit>()
            startBlockingScan(ScanStub.Gated(gate))
            // Workers created from here on finish immediately; only the blocker is gated.
            ScanStubWorkerFactory.behavior = ScanStub.Succeed()

            composeRule.runOnUiThread { library.saveNewArrivalsFolder(arrivals) }
            val notice = await(library.newArrivalsFolderNotice) { it != null }

            // Informational channel only: nothing may land in the red error slot.
            assertEquals(context.getString(R.string.status_new_arrivals_scan_deferred), notice)
            assertNull(library.newArrivalsFolderError.value)
            val tracked = workManager.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).get()
                .single { it.state == WorkInfo.State.BLOCKED }
            // Still waiting: the message must still be up, not on a timer.
            Thread.sleep(500)
            assertEquals(notice, library.newArrivalsFolderNotice.value)

            // The blocking scan finishes; the deferred one is released and runs. The message
            // describes a wait that is over, so it must go — not on a timer, not on the next pick.
            gate.complete(Unit)
            awaitState(tracked.id, WorkInfo.State.SUCCEEDED)
            await(library.newArrivalsFolderNotice) { it == null }
            assertNull(library.newArrivalsFolderError.value)
        } finally {
            hot.cancel()
        }
    }

    @Test fun deferredScanMessageAlsoClearsWhenItsScanIsCancelledOrFails() {
        saveMusic()
        val hot = keepNoticeHot()
        try {
            startBlockingScan()
            composeRule.runOnUiThread { library.saveNewArrivalsFolder(arrivals) }
            await(library.newArrivalsFolderNotice) { it != null }
            val tracked = workManager.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).get()
                .single { it.state == WorkInfo.State.BLOCKED }

            // Everything under the shared name goes, including the deferred request itself.
            workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).result.get()

            awaitState(tracked.id, WorkInfo.State.CANCELLED)
            await(library.newArrivalsFolderNotice) { it == null }
        } finally {
            hot.cancel()
        }
    }

    @Test fun noDeferredMessageWhenNothingWasRunning() {
        saveMusic()
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        val hot = keepNoticeHot()
        try {
            composeRule.runOnUiThread { library.saveNewArrivalsFolder(arrivals) }
            await(library.newArrivalsFolderUri) { it == arrivals }
            assertNull(library.newArrivalsFolderNotice.value)
            assertNull(library.newArrivalsFolderError.value)
        } finally {
            hot.cancel()
        }
    }

    /** A real error still uses the red channel, untouched by the informational one. */
    @Test fun nestedPickStillReportsThroughTheErrorChannelOnly() {
        saveMusic()
        val hot = keepNoticeHot()
        try {
            composeRule.runOnUiThread { library.saveNewArrivalsFolder(music) }
            await(library.newArrivalsFolderError) { it != null }
            assertNull(library.newArrivalsFolderNotice.value)
        } finally {
            hot.cancel()
        }
    }

    // --- what the real wizard screens show -----------------------------------------------

    /** A cancelled picker is silent everywhere; this is the one screen where it strands the user. */
    @Test fun freshInstallFolderStepExplainsWhyDoneIsDisabledUntilAMusicFolderIsPicked() {
        val hint = context.getString(R.string.onboarding_music_folder_required_hint)
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_folder_setup_title)).assertExists()

        composeRule.onNodeWithText(hint).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsNotEnabled()

        composeRule.runOnUiThread { library.saveOnboardingMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(hint).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled()
    }

    @Test fun recoveryFolderStepExplainsWhyDoneIsDisabledUntilAMusicFolderIsPicked() {
        val hint = context.getString(R.string.onboarding_music_folder_required_hint)
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recover_backup)).performClick()

        composeRule.onNodeWithText(hint).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsNotEnabled()

        saveMusic()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(hint).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled()
    }

    /** End to end through the real folder step: neutral colour, and gone once its scan is released. */
    @Test fun deferredScanMessageRendersNeutralOnTheFolderStepAndDisappearsWithItsScan() {
        val deferred = context.getString(R.string.status_new_arrivals_scan_deferred)
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        composeRule.runOnUiThread { library.saveOnboardingMusicFolder(music) }
        await(library.musicFolderUri) { it == music }

        val gate = CompletableDeferred<Unit>()
        startBlockingScan(ScanStub.Gated(gate))
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        composeRule.runOnUiThread { library.saveNewArrivalsFolder(arrivals) }

        awaitText(deferred)
        assertEquals(TextSecondary, textColorOf(deferred))
        assertNotEquals(Error, textColorOf(deferred))

        gate.complete(Unit)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(deferred).fetchSemanticsNodes().isEmpty()
        }
    }

    /** The red channel is unchanged: a real rejection is still Error-coloured. */
    @Test fun nestedNewArrivalsPickStillRendersInErrorColour() {
        val nested = context.getString(R.string.error_folders_nested)
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        composeRule.runOnUiThread { library.saveOnboardingMusicFolder(music) }
        await(library.musicFolderUri) { it == music }

        composeRule.runOnUiThread { library.saveNewArrivalsFolder(music) }

        awaitText(nested)
        assertEquals(Error, textColorOf(nested))
    }

    @Test fun refusedGrantMessageGivesActionableAdviceInsteadOfRetryAdvice() {
        val message = context.getString(R.string.error_folder_grant_refused)
        assertTrue(message, message.contains("can’t be remembered"))
        assertTrue(message, message.contains("internal storage"))
        assertFalse(message, message.contains("try again", ignoreCase = true))
    }
}
