package com.v1per.synthx.ui.screens.onboarding

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
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
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupTier
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
    private val workManager get() = WorkManager.getInstance(context)
    private val music = Uri.parse("content://synthx.test.music/tree/primary%3AMusic")
    private val arrivals = Uri.parse("content://synthx.test.arrivals/tree/primary%3AArrivals")

    @Before fun setUp() {
        TestWorkManager.ensureInitialized(context)
        val repository = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "getMissingSongCount") { "Unexpected repository access: ${method.name}" }
            flowOf(0)
        } as SongRepository
        preferences.lastBackupAttemptTimestamp = 123L
        composeRule.runOnUiThread {
            library = LibraryManagementViewModel(context, preferences, repository, BackupManager(context))
            backups = BackupsAndRestoreViewModel(context, preferences, BackupManager(context))
            restore = RestoreViewModel(context, BackupManager(context), preferences)
            onboarding = OnboardingViewModel()
            listOf(library, backups, restore, onboarding).forEach { store.put(it.javaClass.name, it) }
        }
    }

    @After fun tearDown() {
        composeRule.runOnUiThread { store.clear() }
        // TestWorkManager holds requests in its test database. Constraints are never satisfied,
        // so the restore submission test cannot run RestoreWorker or replace a live database.
        workManager.cancelUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
        context.deleteDatabase("$testId.db")
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

    @Test fun confirmedRestoreUsesProductionCompletionAndRemovesWizardFromBackStack() {
        // A standalone, empty Room database is enough for the existing preview's schema/count
        // checks. Only the preview reads it; TestWorkManager never runs the restore request.
        val databaseName = "$testId.db"
        val database = SynthXDatabase.createStandalone(context, databaseName)
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
        val candidate = backup(Uri.fromFile(context.getDatabasePath(databaseName)))
        saveMusic()
        startWizard()
        composeRule.runOnUiThread { onboarding.goToRecoveryBackupSelect() }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_recovery_backup_title)).assertExists()
        composeRule.runOnUiThread { restore.beginPreview(candidate) }
        await(restore.preview) { it is RestorePreview.Ready || it is RestorePreview.Failed }
        assertTrue(restore.preview.value is RestorePreview.Ready)
        // Exercise cancel first: it must leave the wizard and its current step intact.
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeRule.runOnIdle { assertEquals(Screen.Onboarding.route, navController.currentDestination?.route) }
        composeRule.runOnUiThread { restore.beginPreview(candidate) }
        await(restore.preview) { it is RestorePreview.Ready }
        composeRule.onNodeWithText(context.getString(R.string.restore_confirm_action)).performClick()
        composeRule.onNodeWithText("Home after onboarding").assertExists()
        composeRule.runOnIdle {
            assertEquals(Screen.Home.route, navController.currentDestination?.route)
            assertNull(navController.previousBackStackEntry)
        }
        val requests = workManager.getWorkInfosForUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).get()
        assertEquals(1, requests.size)
        assertEquals(WorkInfo.State.ENQUEUED, requests.single().state)
    }

    private fun saveMusic() {
        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
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

    private fun startWizard() {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                LibraryManagementViewModel::class.java -> library
                BackupsAndRestoreViewModel::class.java -> backups
                RestoreViewModel::class.java -> restore
                OnboardingViewModel::class.java -> onboarding
                else -> error("Unexpected ViewModel: $modelClass")
            } as T
        }
        composeRule.setContent {
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
                NavHost(navController, startDestination = Screen.Onboarding.route) {
                    onboardingRoutes(navController)
                    composable(Screen.Home.route) { Text("Home after onboarding") }
                }
            }
        }
    }
}
