package com.v1per.synthx.ui.screens.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupFileNames
import com.v1per.synthx.data.backup.BackupHashing
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupMetadata
import com.v1per.synthx.data.backup.BackupMetadataCodec
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestorePermitLeakCheck
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.backup.pruneFinishedWorkIfRestoreAcknowledged
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.shouldShowOnboarding
import com.v1per.synthx.ui.onboardingRoutes
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.FirstScanState
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.screens.restore.RestoreOutcome
import com.v1per.synthx.ui.screens.restore.RestorePreview
import com.v1per.synthx.ui.screens.restore.RestoreViewModel
import com.v1per.synthx.ui.components.LocalNotificationPermissionBackend
import com.v1per.synthx.ui.components.NotificationPermissionBackend
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextSecondary
import java.lang.reflect.Proxy
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Uses isolated preferences and a repository stub; never restores over the app database. */
// Test-only restricted API use; there is no public equality-based alternative.
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class OnboardingRecoveryInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val testName = TestName()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "onboarding-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    private val preferences = PreferencesManager(context)
    private val store = ViewModelStore()
    private var systemPromptLaunches = 0
    private val notificationBackend = NotificationPermissionBackend(
        preferences = preferences,
        isGranted = { false },
        launchRequest = { systemPromptLaunches++ },
    )
    private lateinit var library: LibraryManagementViewModel
    private lateinit var backups: BackupsAndRestoreViewModel
    private lateinit var restore: RestoreViewModel
    private lateinit var onboarding: OnboardingViewModel
    private lateinit var navController: NavHostController
    private val persistedGrants = mutableSetOf<Uri>()
    private val releasedGrants = mutableListOf<Uri>()
    private val createdFiles = mutableListOf<File>()
    private val workManager get() = WorkManager.getInstance(context)
    private val music = "content://synthx.test.music/tree/primary%3AMusic".toUri()
    private val arrivals = "content://synthx.test.arrivals/tree/primary%3AArrivals".toUri()

    @Before fun setUp() {
        TestWorkManager.ensureInitialized(context)
        // Done and restore-confirm put the notification rationale in front of the user
        // once. Every test below that isn't about that rationale drives the wizard as if it had
        // already been answered; the notificationRationale* tests at the end opt out by name and
        // cover the rationale itself.
        if (!testName.methodName.startsWith("notificationRationale")) {
            check(preferences.markNotificationPermissionAskedDurably())
        }
        ScanStubWorkerFactory.behavior = null
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
        RestorePermitLeakCheck.assertDrainedThenReset()
        val repository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "getMissingSongCount") { "Unexpected repository access: ${method.name}" }
            flowOf(0)
        } as SongRepository
        preferences.lastBackupAttemptTimestamp = 123L
        persistedGrants.clear()
        releasedGrants.clear()
        createdFiles.clear()
        composeRule.runOnUiThread {
            library = LibraryManagementViewModel(
                context,
                preferences,
                repository,
                BackupManager(context),
                releasePersistableGrant = { uri ->
                    releasedGrants += uri
                    persistedGrants -= uri
                },
            )
            backups = BackupsAndRestoreViewModel(context, preferences, BackupManager(context))
            restore = RestoreViewModel(context, BackupManager(context), preferences)
            onboarding = OnboardingViewModel(preferences)
            listOf(library, backups, restore, onboarding).forEach { store.put(it.javaClass.name, it) }
        }
    }

    @After fun tearDown() {
        composeRule.runOnUiThread { store.clear() }
        // Hang stubs must not outlive their test: cancel first, then drop the override.
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).result.get()
        ScanStubWorkerFactory.behavior = null
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
        if (RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.RESTORE_PREPARING) {
            RestoreCoordinator.markNormal()
        }
        createdFiles.forEach { file ->
            context.deleteDatabase(file.name)
            file.delete()
        }
        appContext.deleteSharedPreferences("$testId-synthx_prefs")
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    @Test fun recoveryAudioPicksPersistWithoutScansOrBackupAttempts() {
        val before = scanIds()
        saveMusic()
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertNull(preferences.newArrivalsFolderUri)
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(arrivals) }
        await(library.newArrivalsFolderUri) { it == arrivals }
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertEquals(before, scanIds())
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
    }

    @Test fun nestedRecoveryPickKeepsThePreviouslyAcceptedFolder() {
        saveMusic()
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(arrivals) }
        await(library.newArrivalsFolderUri) { it == arrivals }
        val nested = "content://synthx.test.music/tree/primary%3AMusic%2FArrivals".toUri()
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(nested) }
        await(library.newArrivalsFolderError) { it != null }
        assertEquals(arrivals, library.newArrivalsFolderUri.value)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)

        val containsArrivals = "content://synthx.test.arrivals/tree/primary%3A".toUri()
        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(containsArrivals) }
        await(library.folderError) { it != null }
        assertEquals(music, library.musicFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
    }

    @Test fun sameFolderRejectionPreservesBothGrantsThroughFreshInstallSaveFunctions() {
        configureAudioRoots()
        persistedGrants += setOf(music, arrivals)

        composeRule.runOnUiThread { library.saveMusicFolder(arrivals) }
        await(library.folderError) { it != null }
        composeRule.runOnIdle { }
        assertEquals(music, library.musicFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertTrue(arrivals in persistedGrants)

        composeRule.runOnUiThread { library.saveNewArrivalsFolder(music) }
        await(library.newArrivalsFolderError) { it != null }
        composeRule.runOnIdle { }
        assertEquals(arrivals, library.newArrivalsFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertTrue(music in persistedGrants)
        assertTrue(releasedGrants.isEmpty())
    }

    @Test fun sameFolderRejectionPreservesBothGrantsThroughRecoverySaveFunctions() {
        configureAudioRoots()
        persistedGrants += setOf(music, arrivals)

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(arrivals) }
        await(library.folderError) { it != null }
        composeRule.runOnIdle { }
        assertEquals(music, library.musicFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertTrue(arrivals in persistedGrants)

        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(music) }
        await(library.newArrivalsFolderError) { it != null }
        composeRule.runOnIdle { }
        assertEquals(arrivals, library.newArrivalsFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertTrue(music in persistedGrants)
        assertTrue(releasedGrants.isEmpty())
    }

    @Test fun rejectedOrphanGrantIsStillReleased() {
        configureAudioRoots()
        val orphan = "content://synthx.test.arrivals/tree/primary%3AArrivals%2FRejected".toUri()
        persistedGrants += orphan

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(orphan) }
        await(library.folderError) { it != null }
        composeRule.runOnIdle { }

        assertFalse(orphan in persistedGrants)
        assertEquals(listOf(orphan), releasedGrants)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
    }

    @Test fun rejectedBackupOwnedGrantIsPreserved() {
        configureAudioRoots()
        val backup = "content://synthx.test.arrivals/tree/primary%3AArrivals%2FBackups".toUri()
        preferences.backupFolderUri = backup.toString()
        persistedGrants += backup

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(backup) }
        await(library.folderError) { it != null }
        composeRule.runOnIdle { }

        assertTrue(backup in persistedGrants)
        assertTrue(releasedGrants.isEmpty())
        assertEquals(backup.toString(), preferences.backupFolderUri)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
    }

    @Test fun supersededAudioRootDoesNotReleaseGrantStillOwnedByBackupRole() {
        configureAudioRoots()
        preferences.backupFolderUri = music.toString()
        persistedGrants += music
        val replacement = "content://synthx.test.replacement/tree/primary%3AReplacement".toUri()

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(replacement) }
        await(library.musicFolderUri) { it == replacement }
        composeRule.runOnIdle { }

        assertTrue(music in persistedGrants)
        assertTrue(releasedGrants.isEmpty())
        assertEquals(music.toString(), preferences.backupFolderUri)
        assertEquals(replacement.toString(), preferences.musicFolderUri)
    }

    @Test fun genuinelySupersededAudioRootGrantIsStillReleased() {
        configureAudioRoots()
        persistedGrants += music
        val replacement = "content://synthx.test.replacement/tree/primary%3AReplacement".toUri()

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(replacement) }
        await(library.musicFolderUri) { it == replacement }
        composeRule.runOnIdle { }

        assertFalse(music in persistedGrants)
        assertEquals(listOf(music), releasedGrants)
        assertEquals(replacement.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
    }

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

    /**
     * The dialog's confirm button stays disabled until the required word is typed exactly, so a
     * bare click on it is a silent no-op that never enqueues the restore. Once typed, the field's own text also reads the required word
     * and is itself clickable — exclude anything with a set-text action to isolate the button.
     */
    private fun confirmRestoreWithTypedWord() {
        val requiredWord = context.getString(R.string.restore_confirm_action)
        composeRule.onNode(hasSetTextAction()).performTextInput(requiredWord)
        composeRule
            .onNode(hasText(requiredWord).and(hasClickAction()).and(!hasSetTextAction()))
            .assertIsEnabled()
            .performClick()
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

    // ------------------------------------------------------------------ notification rationale

    private fun rationaleTitle() = context.getString(R.string.notification_permission_title)

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

    private fun saveOnboardingMusic() {
        composeRule.runOnUiThread { library.saveOnboardingMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
    }

    /** Fresh install → pick the music folder → tap Done, leaving the wizard in/after ScanningStep. */
    private fun startWizardAtDone() {
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled().performClick()
    }

    // --- the deferred-scan message is informational and clears with its scan ------

    /** Keeps [newArrivalsFolderNotice]'s WhileSubscribed upstream alive for the whole test. */
    private fun keepNoticeHot(): kotlinx.coroutines.Job =
        CoroutineScope(Dispatchers.Default).launch { library.newArrivalsFolderNotice.collect { } }

    private fun awaitState(id: UUID, state: WorkInfo.State) = runBlocking {
        withTimeout(10_000) {
            workManager.getWorkInfoByIdFlow(id).first { it?.state == state }
        }
    }

    /** A real scan (hanging stub) already running under the shared unique name. */
    private fun startBlockingScan(behavior: ScanStub = ScanStub.Hang): UUID {
        ScanStubWorkerFactory.behavior = behavior
        val blocker = LibraryScanWorker.enqueue(context, ExistingWorkPolicy.REPLACE).id
        awaitState(blocker, WorkInfo.State.RUNNING)
        return blocker
    }

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

    private fun textColorOf(text: String): androidx.compose.ui.graphics.Color {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

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
        assertTrue(message, message.contains("can't be remembered"))
        assertTrue(message, message.contains("internal storage"))
        assertFalse(message, message.contains("try again", ignoreCase = true))
    }

    /** For state that arrives from WorkManager's own threads, which waitForIdle does not cover. */
    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun emptyRepository(): SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        check(method.name == "getMissingSongCount") { "Unexpected repository access: ${method.name}" }
        flowOf(0)
    } as SongRepository

    private fun saveMusic() {
        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
    }

    private fun configureAudioRoots() {
        saveMusic()
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(arrivals) }
        await(library.newArrivalsFolderUri) { it == arrivals }
    }

    private fun scanIds() =
        workManager.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).get().map { it.id }

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean) = runBlocking {
        withTimeout(10_000) { flow.first(predicate) }
    }

    private fun backup(uri: Uri) = BackupInfo(
        databaseUri = uri,
        metadataUri = null,
        fileName = "onboarding-preview.db",
        tier = BackupTier.Manual,
        timestamp = System.currentTimeMillis(),
        reason = null,
        sizeBytes = 0,
        restoreEligible = true,
    )

    private fun validCandidateWithMetadata(): Pair<BackupInfo, File> {
        val names = BackupFileNames.create()
        val databaseName = names.databaseFileName
        val source = context.getDatabasePath(databaseName)
        val database = SynthXDatabase.createStandalone(context, databaseName)
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
        val digest = BackupHashing.hash(source)
        val metadataFile = File(source.parentFile, names.metadataFileName)
        createdFiles += source
        createdFiles += metadataFile
        metadataFile.writeBytes(
            BackupMetadataCodec.encode(
                BackupMetadata(
                    timestamp = System.currentTimeMillis(),
                    tier = BackupTier.Manual.name,
                    dbVersion = SynthXDatabase.VERSION,
                    file = source.name,
                    sizeBytes = digest.sizeBytes,
                    sha256 = digest.sha256,
                ),
            ),
        )
        return BackupInfo(
            databaseUri = Uri.fromFile(source),
            metadataUri = Uri.fromFile(metadataFile),
            fileName = source.name,
            tier = BackupTier.Manual,
            timestamp = System.currentTimeMillis(),
            reason = null,
            sizeBytes = source.length(),
            restoreEligible = true,
        ) to source
    }

    private fun awaitRequest(): WorkInfo = runBlocking {
        withTimeout(10_000) {
            workManager.getWorkInfosForUniqueWorkFlow(RestoreWorker.UNIQUE_WORK_NAME)
                .first { infos -> infos.singleOrNull()?.state == WorkInfo.State.ENQUEUED }
                .single()
        }
    }

    @Composable
    private fun WizardContent(factory: ViewModelProvider.Factory, startDestination: String) {
        // The notification gate reads this wizard's isolated preferences (so the "asked"
        // flag the tests set/inspect is the one it sees) and counts what would have been the
        // system prompt instead of showing it.
        CompositionLocalProvider(LocalNotificationPermissionBackend provides notificationBackend) {
            SynthXTheme {
                navController = rememberNavController().also { controller ->
                    controller.addOnDestinationChangedListener { nav, destination, _ ->
                        if (destination.route == Screen.Onboarding.route) {
                            val provider = ViewModelProvider(nav.getBackStackEntry(Screen.Onboarding.route), factory)
                            provider[LibraryManagementViewModel::class.java]
                            provider[BackupsAndRestoreViewModel::class.java]
                            provider[RestoreViewModel::class.java]
                            provider[OnboardingViewModel::class.java]
                        }
                    }
                }
                NavHost(navController, startDestination = startDestination) {
                    onboardingRoutes(navController)
                    composable(Screen.Home.route) { Text("Home after onboarding") }
                }
            }
        }
    }

    private fun wizardFactory(
        library: LibraryManagementViewModel,
        backups: BackupsAndRestoreViewModel,
        restore: RestoreViewModel,
        onboarding: OnboardingViewModel,
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            LibraryManagementViewModel::class.java -> library
            BackupsAndRestoreViewModel::class.java -> backups
            RestoreViewModel::class.java -> restore
            OnboardingViewModel::class.java -> onboarding
            else -> error("Unexpected ViewModel: $modelClass")
        } as T
    }

    /** MainActivity's real cold-start decision ([shouldShowOnboarding]), not a local copy of it. */
    private fun gatingStartDestination(): String =
        if (shouldShowOnboarding(preferences)) Screen.Onboarding.route else Screen.Home.route

    private fun startWizard(
        library: LibraryManagementViewModel = this.library,
        backups: BackupsAndRestoreViewModel = this.backups,
        restore: RestoreViewModel = this.restore,
        onboarding: OnboardingViewModel = this.onboarding,
        startDestination: String = gatingStartDestination(),
    ) {
        val factory = wizardFactory(library, backups, restore, onboarding)
        composeRule.setContent { WizardContent(factory, startDestination) }
    }

    /**
     * Simulates process death mid-wizard: discards the in-memory ViewModel instances (a fresh
     * quartet, exactly as a real process recreation would produce) and recomposes onto the same
     * Activity with a brand new NavHostController and backstack, while [preferences]' backing
     * SharedPreferences file — the one thing that actually survives — is left untouched.
     * [gating] lets [mutationTestLegacyGatingConditionWouldHaveShownHomeInstead] substitute the
     * older condition without needing to edit MainActivity.kt itself.
     */
    private fun relaunchWizardAfterSimulatedProcessDeath(gating: () -> String = ::gatingStartDestination) {
        val repository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "getMissingSongCount") { "Unexpected repository access: ${method.name}" }
            flowOf(0)
        } as SongRepository
        composeRule.runOnUiThread {
            library = LibraryManagementViewModel(
                context,
                preferences,
                repository,
                BackupManager(context),
                releasePersistableGrant = { uri ->
                    releasedGrants += uri
                    persistedGrants -= uri
                },
            )
            backups = BackupsAndRestoreViewModel(context, preferences, BackupManager(context))
            restore = RestoreViewModel(context, BackupManager(context), preferences)
            onboarding = OnboardingViewModel(preferences)
            store.clear()
            listOf(library, backups, restore, onboarding).forEach { store.put(it.javaClass.name, it) }
        }
        val factory = wizardFactory(library, backups, restore, onboarding)
        val startDestination = gating()
        composeRule.runOnUiThread {
            composeRule.activity.setContent { WizardContent(factory, startDestination) }
        }
        composeRule.waitForIdle()
    }
}
