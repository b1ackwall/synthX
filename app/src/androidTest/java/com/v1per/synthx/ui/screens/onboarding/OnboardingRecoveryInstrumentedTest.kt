package com.v1per.synthx.ui.screens.onboarding

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.onboardingRoutes
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.screens.restore.RestoreOutcome
import com.v1per.synthx.ui.screens.restore.RestorePreview
import com.v1per.synthx.ui.screens.restore.RestoreViewModel
import com.v1per.synthx.ui.theme.SynthXTheme
import java.lang.reflect.Proxy
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses isolated preferences and a repository stub; never restores over the app database. */
@RunWith(AndroidJUnit4::class)
class OnboardingRecoveryInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "onboarding-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    private val preferences = PreferencesManager(context)
    private val store = ViewModelStore()
    private lateinit var library: LibraryManagementViewModel
    private lateinit var backups: BackupsAndRestoreViewModel
    private lateinit var restore: RestoreViewModel
    private lateinit var onboarding: OnboardingViewModel
    private lateinit var navController: NavHostController
    private val persistedGrants = mutableSetOf<Uri>()
    private val releasedGrants = mutableListOf<Uri>()
    private val createdFiles = mutableListOf<File>()
    private val workManager get() = WorkManager.getInstance(context)
    private val music = Uri.parse("content://synthx.test.music/tree/primary%3AMusic")
    private val arrivals = Uri.parse("content://synthx.test.arrivals/tree/primary%3AArrivals")

    @Before fun setUp() {
        TestWorkManager.ensureInitialized(context)
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
        if (RestoreCoordinator.activeOperationCountForTest() == 0) RestoreCoordinator.resetForTest()
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
        val nested = Uri.parse("content://synthx.test.music/tree/primary%3AMusic%2FArrivals")
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(nested) }
        await(library.newArrivalsFolderError) { it != null }
        assertEquals(arrivals, library.newArrivalsFolderUri.value)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)

        val containsArrivals = Uri.parse("content://synthx.test.arrivals/tree/primary%3A")
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
        val orphan = Uri.parse(
            "content://synthx.test.arrivals/tree/primary%3AArrivals%2FRejected",
        )
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
        val backup = Uri.parse(
            "content://synthx.test.arrivals/tree/primary%3AArrivals%2FBackups",
        )
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
        val replacement = Uri.parse(
            "content://synthx.test.replacement/tree/primary%3AReplacement",
        )

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
        val replacement = Uri.parse(
            "content://synthx.test.replacement/tree/primary%3AReplacement",
        )

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
        assertEquals(before.map { it.id },
            workManager.getWorkInfosForUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).get().map { it.id })
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
        composeRule.onNodeWithText(context.getString(R.string.restore_confirm_action)).performClick()
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
        composeRule.onNodeWithText(context.getString(R.string.restore_confirm_action)).performClick()
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

    /** Acceptance test for P7-T2 / R7-F02 + R7-F13. */
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

    /**
     * Mutation test for the acceptance test above: substituting the pre-P7-T2 gating condition
     * (musicFolderUri == null) reproduces R7-F02 exactly — the wizard vanishes and Home is
     * shown with an empty library instead. Kept as a permanent, automated guard rather than a
     * one-off manual revert of MainActivity.kt, since this suite already mirrors that gating
     * condition locally instead of launching the real Activity (see [gatingStartDestination]).
     */
    @Test fun mutationTestLegacyGatingConditionWouldHaveShownHomeInstead() {
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recover_backup)).performClick()
        saveMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertExists()

        relaunchWizardAfterSimulatedProcessDeath(gating = ::legacyGatingStartDestination)

        composeRule.onNodeWithText("Home after onboarding").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertDoesNotExist()
    }

    @Test fun pickingRecoveryFolderAloneDoesNotSetOnboardingComplete() {
        saveMusic()
        composeRule.runOnIdle { }
        assertFalse(preferences.onboardingComplete)
    }

    /** Regression protection: fresh installs never had a scan/restore step, only a folder pick. */
    @Test fun freshInstallSetsOnboardingCompleteAtFolderPickTime() {
        assertFalse(preferences.onboardingComplete)
        composeRule.runOnUiThread { library.saveMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
        composeRule.runOnIdle { }
        assertTrue(preferences.onboardingComplete)
        assertNull(preferences.onboardingStepName)
    }

    /**
     * Regression guard for MainActivity.runLaunchMaintenance()'s separate, deliberately
     * untouched `musicFolderUri != null` scan-trigger check (see MainActivity.kt) — proves the
     * two preferences stay independent rather than getting coupled by this fix. MainActivity
     * itself is not launched by this suite (no existing harness does so, see also
     * [gatingStartDestination]'s local mirror), so this asserts the underlying preference
     * invariant that check actually depends on.
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
                )
            )
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

    /** Mirrors MainActivity's cold-start gating condition post P7-T2 — see MainActivity.kt:107. */
    private fun gatingStartDestination(): String =
        if (preferences.onboardingComplete) Screen.Home.route else Screen.Onboarding.route

    /**
     * Mirrors MainActivity's PRE-P7-T2 gating condition (R7-F02's exact bug). Used only by
     * [mutationTestLegacyGatingConditionWouldHaveShownHomeInstead] to prove the acceptance test
     * actually exercises the fix rather than passing vacuously.
     */
    private fun legacyGatingStartDestination(): String =
        if (preferences.musicFolderUri == null) Screen.Onboarding.route else Screen.Home.route

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
     * pre-fix condition without needing to edit MainActivity.kt itself.
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
