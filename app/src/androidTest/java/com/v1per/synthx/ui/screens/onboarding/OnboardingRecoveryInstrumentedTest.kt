package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.shouldShowOnboarding
import com.v1per.synthx.ui.screens.librarymanagement.FirstScanState
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses isolated preferences and a repository stub; never restores over the app database. */
@RunWith(AndroidJUnit4::class)
class OnboardingRecoveryInstrumentedTest : OnboardingRecoveryTestBase() {

    /**
     * Fresh install must not complete onboarding (or enqueue the first scan) at the folder *pick*,
     * before the user has confirmed anything. Picking only persists the folder; completion, the
     * scan and the tracked request id all happen together at Done
     * ([LibraryManagementViewModel.startFirstScan]). Also guards that a fresh install never
     * attempts a backup that cannot exist yet.
     */
    @Test fun freshInstallSetsOnboardingCompleteAtDoneNotAtFolderPick() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        val before = scanIds()
        assertFalse(preferences.onboardingComplete)

        composeRule.runOnUiThread { library.saveOnboardingMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
        composeRule.runOnIdle { }
        // Pick time: folder persisted, and nothing else.
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertFalse(preferences.onboardingComplete)
        assertNull(preferences.firstScanRequestId)
        assertEquals(before, scanIds())
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)

        var started: UUID? = null
        composeRule.runOnUiThread { started = library.startFirstScan() }
        // Done time: completion + scan + tracked id land together, id matches the real request.
        assertTrue(preferences.onboardingComplete)
        assertNull(preferences.onboardingStepName)
        assertEquals(started.toString(), preferences.firstScanRequestId)
        assertTrue(started in scanIds())
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
    }

    /**
     * Acceptance test: nothing has actually been backed up yet at this point
     * on a fresh install — the backup folder is a later, separate step — so saveMusicFolder must
     * skip the attempt entirely rather than let it fail and have BackupOutcomeStore durably
     * record a false "Failed" health warning for an operation that was never possible.
     */
    @Test fun saveMusicFolderSkipsBackupAttemptWhenNoBackupFolderConfigured() {
        assertNull(preferences.backupFolderUri)
        val outcomeBefore = BackupOutcomeStore.lastBackupOutcome.value

        composeRule.runOnUiThread { library.saveMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
        composeRule.runOnIdle { }

        // Sentinel from setUp() — unchanged proves createBackup was never invoked at all, not
        // merely that a thrown exception didn't propagate.
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
        assertSame(outcomeBefore, BackupOutcomeStore.lastBackupOutcome.value)
        // The Library Management re-pick path does not own onboarding completion — that is
        // startFirstScan's job, at Done.
        assertFalse(preferences.onboardingComplete)
    }

    /**
     * The onboarding path itself (pick, then Done) can never attempt or record a backup: with the
     * backup folder already configured, [saveOnboardingMusicFolder] is persist-only and
     * [startFirstScan] never snapshots. A fresh-install database is empty, so there is nothing to
     * protect, and a snapshot of it would only take a retention slot from a real backup.
     */
    @Test fun onboardingPickAndDoneNeverAttemptBackupEvenWithBackupFolderConfigured() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        preferences.backupFolderUri = arrivals.toString()
        val outcomeBefore = BackupOutcomeStore.lastBackupOutcome.value

        composeRule.runOnUiThread { library.saveOnboardingMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
        composeRule.runOnUiThread { library.startFirstScan() }
        composeRule.runOnIdle { }

        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
        assertSame(outcomeBefore, BackupOutcomeStore.lastBackupOutcome.value)
        assertTrue(preferences.onboardingComplete)
    }

    /**
     * Edge-case regression: FolderSetupStep lets the backup folder be picked before the (only
     * mandatory) music folder within the same onboarding step — nothing enforces picker order.
     * When that happens, saveMusicFolder's Auto backup must still fire; the no-backup-folder
     * pre-check must only suppress the attempt when nothing is configured, not narrow it further.
     * (This guards the Library Management re-pick path — onboarding's own pick is persist-only,
     * see [onboardingPickAndDoneNeverAttemptBackupEvenWithBackupFolderConfigured].)
     */
    @Test fun saveMusicFolderStillAttemptsBackupWhenBackupFolderAlreadyConfigured() {
        preferences.backupFolderUri = arrivals.toString()

        composeRule.runOnUiThread { library.saveMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
        composeRule.runOnIdle { }

        // The attempt fails (arrivals isn't a real backup tree) but must still be RECORDED —
        // proving createBackup was actually invoked, unlike the no-folder-configured case above.
        assertNotEquals(123L, preferences.lastBackupAttemptTimestamp)
        assertFalse(preferences.onboardingComplete)
    }

    /**
     * Acceptance test for switching back to Choice without an interruption: explore
     * recovery, persist its music folder via saveRecoveryMusicFolder, back out to Choice without
     * ever completing the restore, then pick Fresh Install. Must NOT re-attempt a backup snapshot
     * of a folder whose contents haven't changed since being picked — but still must complete
     * onboarding and trigger the first scan, exactly as a genuine fresh install would.
     */
    @Test fun freshInstallFromAbandonedRecoveryDoesNotReattemptBackupButStillScansAndCompletesAtDone() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recover_backup)).performClick()
        saveMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled().performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_folder_title)).assertExists()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_welcome_title)).assertExists()
        assertFalse(preferences.onboardingComplete)
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
        assertTrue(scanIds().isEmpty())

        // Switching to fresh install starts nothing by itself (Done does): the recovered folder
        // just shows up.
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_folder_setup_title)).assertExists()
        assertFalse(preferences.onboardingComplete)
        assertTrue(scanIds().isEmpty())
        assertNull(preferences.firstScanRequestId)

        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled().performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_scanning_title)).assertExists()

        // Still no re-attempted backup on the already-persisted recovery folder.
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
        // The two genuine fresh-install side effects still land — at Done, tracked by id.
        assertTrue(preferences.onboardingComplete)
        val scanId = UUID.fromString(preferences.firstScanRequestId)
        assertEquals(listOf(scanId), scanIds())
    }

    /**
     * Regression guard for MainActivity.runLaunchMaintenance()'s separate
     * `musicFolderUri != null` scan-trigger check (see MainActivity.kt) — proves the two
     * preferences stay independent. MainActivity itself is not launched by this suite (see
     * [gatingStartDestination]), so this asserts the underlying preference invariant that check
     * actually depends on.
     */
    @Test fun musicFolderUriAndOnboardingCompleteAreIndependentPreferences() {
        saveMusic()
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertFalse(preferences.onboardingComplete)

        composeRule.runOnUiThread { check(preferences.setOnboardingCompleteDurably(true)) }
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertTrue(preferences.onboardingComplete)

        preferences.musicFolderUri = null
        assertTrue(preferences.onboardingComplete)
        assertNull(preferences.musicFolderUri)
    }

    @Test fun completingOnboardingClearsPersistedStep() {
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recover_backup)).performClick()
        saveMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertExists()
        assertEquals(OnboardingStep.RecoveryBackupSelect.name, preferences.onboardingStepName)

        composeRule.runOnUiThread { check(preferences.setOnboardingCompleteDurably(true)) }

        assertNull(preferences.onboardingStepName)
        assertTrue(preferences.onboardingComplete)
    }

    /**
     * Done-time counterpart of [completingOnboardingClearsPersistedStep]: the fresh-install
     * wizard's own completion point is the moment the scan is accepted. Until then the step
     * (Scanning) and request id stay persisted; accepting the scan clears both together.
     */
    @Test fun acceptingTheFirstScanClearsPersistedStepAndTrackedId() {
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()

        composeRule.onNodeWithText("Home after onboarding").assertExists()
        assertTrue(preferences.onboardingComplete)
        assertNull(preferences.onboardingStepName)
        assertNull(preferences.firstScanRequestId)
        assertFalse(shouldShowOnboarding(preferences))
    }

    // ------------------------------------------------------------------ first scan

    @Test fun startFirstScanWithoutAFolderReturnsNullAndDoesNotCompleteOrScan() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        var started: UUID? = UUID.randomUUID()
        composeRule.runOnUiThread { started = library.startFirstScan() }
        assertNull(started)
        assertFalse(preferences.onboardingComplete)
        assertNull(preferences.firstScanRequestId)
        assertTrue(scanIds().isEmpty())
    }

    @Test fun scanningStepDoesNotAdvanceOnFailedScanAndShowsFailureUi() {
        ScanStubWorkerFactory.behavior = ScanStub.Fail("disk on fire")
        startWizardAtDone()

        awaitText(context.getString(R.string.onboarding_scan_failed_title))
        composeRule.onNodeWithText(context.getString(R.string.scan_status_failed, "disk on fire")).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.action_retry)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_scan_change_folder)).assertExists()
        // A failed scan must not complete onboarding.
        composeRule.onNodeWithText("Home after onboarding").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(Screen.Onboarding.route, navController.currentDestination?.route)
        }
        // Still pending: neither accepted nor abandoned, so a relaunch would land back here.
        assertNotNull(preferences.firstScanRequestId)
        assertTrue(shouldShowOnboarding(preferences))
    }

    @Test fun scanningStepDoesNotAdvanceOnCancelledScanAndShowsCancelledUi() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        startWizardAtDone()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_scanning_title)).assertExists()

        workManager.cancelWorkById(UUID.fromString(preferences.firstScanRequestId)).result.get()

        awaitText(context.getString(R.string.onboarding_scan_cancelled_title))
        composeRule.onNodeWithText(context.getString(R.string.scan_cancelled)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.action_retry)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_scan_change_folder)).assertExists()
        composeRule.onNodeWithText("Home after onboarding").assertDoesNotExist()
    }

    @Test fun backIsBlockedWhileScanningAndLeadsToFolderSetupOnceFailed() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        startWizardAtDone()
        composeRule.waitForIdle()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_scanning_title)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_folder_setup_title)).assertDoesNotExist()
        assertNotNull(preferences.firstScanRequestId)

        workManager.cancelWorkById(UUID.fromString(preferences.firstScanRequestId)).result.get()
        awaitText(context.getString(R.string.onboarding_scan_cancelled_title))

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_folder_setup_title)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_scan_cancelled_title)).assertDoesNotExist()
    }

    @Test fun retryReEnqueuesTheSameFolderUnderANewTrackedIdAndCanSucceed() {
        ScanStubWorkerFactory.behavior = ScanStub.Fail("first attempt")
        startWizardAtDone()
        awaitText(context.getString(R.string.onboarding_scan_failed_title))
        val firstId = UUID.fromString(preferences.firstScanRequestId)

        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        composeRule.onNodeWithText(context.getString(R.string.action_retry)).performClick()

        awaitText("Home after onboarding")
        // Same folder, REPLACE: exactly one scan remains under the name, and it is a new request.
        assertEquals(music.toString(), preferences.musicFolderUri)
        val ids = scanIds()
        assertEquals(1, ids.size)
        assertNotEquals(firstId, ids.single())
        // Accepted → tracking cleared, wizard no longer the cold-start destination.
        assertNull(preferences.firstScanRequestId)
        assertNull(preferences.onboardingStepName)
        assertFalse(shouldShowOnboarding(preferences))
    }

    @Test fun retryTracksTheNewIdWhileItIsStillRunning() {
        ScanStubWorkerFactory.behavior = ScanStub.Fail("first attempt")
        startWizardAtDone()
        awaitText(context.getString(R.string.onboarding_scan_failed_title))
        val firstId = preferences.firstScanRequestId

        ScanStubWorkerFactory.behavior = ScanStub.Hang
        composeRule.onNodeWithText(context.getString(R.string.action_retry)).performClick()

        awaitText(context.getString(R.string.onboarding_scanning_title))
        val retryId = preferences.firstScanRequestId
        assertNotEquals(firstId, retryId)
        assertEquals(listOf(UUID.fromString(retryId)), scanIds())
        assertTrue(preferences.onboardingComplete)
    }

    @Test fun changeFolderReturnsToFolderSetupAndUncompletesOnboarding() {
        ScanStubWorkerFactory.behavior = ScanStub.Fail("nope")
        startWizardAtDone()
        awaitText(context.getString(R.string.onboarding_scan_failed_title))

        composeRule.onNodeWithText(context.getString(R.string.onboarding_scan_change_folder)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.onboarding_folder_setup_title)).assertExists()
        assertNull(preferences.firstScanRequestId)
        assertFalse(preferences.onboardingComplete)
        assertEquals(OnboardingStep.FolderSetup.name, preferences.onboardingStepName)
        assertTrue(shouldShowOnboarding(preferences))
        // The folder they are being asked to change is still shown and Done is still usable.
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled()

        // A process death here must resume at FolderSetup, not land on an empty Home.
        relaunchWizardAfterSimulatedProcessDeath()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_folder_setup_title)).assertExists()
        composeRule.onNodeWithText("Home after onboarding").assertDoesNotExist()
    }

    /**
     * Kill after Done, before the scan completes: the wizard must resume at ScanningStep still
     * tracking the *same* request id — not Home (onboardingComplete is already true by then) and
     * not a fresh scan.
     */
    @Test fun processDeathAfterDoneBeforeScanCompletesResumesAtScanningWithPersistedId() {
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        startWizardAtDone()
        val idBeforeDeath = preferences.firstScanRequestId
        assertNotNull(idBeforeDeath)
        assertTrue(preferences.onboardingComplete)
        assertEquals(OnboardingStep.Scanning.name, preferences.onboardingStepName)

        relaunchWizardAfterSimulatedProcessDeath()

        composeRule.onNodeWithText(context.getString(R.string.onboarding_scanning_title)).assertExists()
        composeRule.onNodeWithText("Home after onboarding").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(Screen.Onboarding.route, navController.currentDestination?.route)
        }
        // Same scan, not a replacement: the id is untouched and its work is still the only one.
        assertEquals(idBeforeDeath, preferences.firstScanRequestId)
        assertEquals(listOf(UUID.fromString(idBeforeDeath)), scanIds())
        // And the resumed ViewModel really observes that id: cancelling it flips the resumed UI.
        workManager.cancelWorkById(UUID.fromString(idBeforeDeath)).result.get()
        awaitText(context.getString(R.string.onboarding_scan_cancelled_title))
    }

    @Test fun processDeathBetweenClearingAndRewritingTheStepStillResumesAtScanning() {
        // Done clears the persisted step (completion) and only re-writes it in goToScanning.
        // Simulate death in that gap: id tracked, complete=true, no step key at all.
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        saveOnboardingMusic()
        composeRule.runOnUiThread { library.startFirstScan() }
        assertNull(preferences.onboardingStepName)

        relaunchWizardAfterSimulatedProcessDeath()

        composeRule.onNodeWithText(context.getString(R.string.onboarding_scanning_title)).assertExists()
    }

    @Test fun processDeathAfterScanFinishedResumesThenHandsOverToHome() {
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        saveOnboardingMusic()
        composeRule.runOnUiThread { library.startFirstScan() }
        val id = preferences.firstScanRequestId
        assertNotNull(id)

        relaunchWizardAfterSimulatedProcessDeath()

        awaitText("Home after onboarding")
        assertNull(preferences.firstScanRequestId)
        assertNull(preferences.onboardingStepName)
        assertFalse(shouldShowOnboarding(preferences))
    }

    @Test fun firstScanWithNoWorkRecordIsEventuallyReportedAsFailedNotSpinnerForever() {
        // An id that resolves to nothing (record pruned / never inserted).
        val orphan = UUID.randomUUID()
        assertTrue(preferences.trackFirstScanRequestDurably(orphan.toString()))
        var orphanLibrary: LibraryManagementViewModel? = null
        composeRule.runOnUiThread {
            orphanLibrary = LibraryManagementViewModel(
                context,
                preferences,
                emptyRepository(),
                BackupManager(context),
            )
        }
        val states = orphanLibrary!!.firstScanState
        val collector = CoroutineScope(Dispatchers.Default).launch { states.collect { } }
        try {
            // Running during the grace window (so a normal enqueue-to-publication gap is not a failure)…
            assertTrue(states.value is FirstScanState.Running)
            // …then Failed rather than an endless spinner.
            runBlocking { withTimeout(20_000) { states.first { it is FirstScanState.Failed } } }
        } finally {
            collector.cancel()
        }
    }

    /**
     * The fresh-install first scan starts and is tracked by the view-model path alone.
     * The notification gate sits in front of Done in the UI, never inside startFirstScan or the
     * worker (stubbed here), so this holds whatever the real POST_NOTIFICATIONS state is.
     */
    @Test fun firstScanStartsAndIsTrackedWithoutConsultingTheNotificationPermission() {
        ScanStubWorkerFactory.behavior = ScanStub.Succeed()
        saveOnboardingMusic()
        var started: UUID? = null
        composeRule.runOnUiThread { started = library.startFirstScan() }

        assertNotNull("scan must start regardless of the notification permission", started)
        assertTrue(preferences.onboardingComplete)
        assertEquals(started.toString(), preferences.firstScanRequestId)
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(scanIds().single()).get()?.state)
    }
}
