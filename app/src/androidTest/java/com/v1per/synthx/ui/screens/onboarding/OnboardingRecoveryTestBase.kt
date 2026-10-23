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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupFileNames
import com.v1per.synthx.data.backup.BackupHashing
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupMetadata
import com.v1per.synthx.data.backup.BackupMetadataCodec
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestorePermitLeakCheck
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.shouldShowOnboarding
import com.v1per.synthx.ui.onboardingRoutes
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.screens.restore.RestoreViewModel
import com.v1per.synthx.ui.components.LocalNotificationPermissionBackend
import com.v1per.synthx.ui.components.NotificationPermissionBackend
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import java.lang.reflect.Proxy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/** Uses isolated preferences and a repository stub; never restores over the app database. */
// Test-only restricted API use; there is no public equality-based alternative.
@SuppressLint("RestrictedApi")
abstract class OnboardingRecoveryTestBase {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val testName = TestName()

    protected val appContext: Context = ApplicationProvider.getApplicationContext()
    protected val testId = "onboarding-test-${UUID.randomUUID()}"
    protected val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    protected val preferences = PreferencesManager(context)
    protected val store = ViewModelStore()
    protected var systemPromptLaunches = 0
    internal val notificationBackend = NotificationPermissionBackend(
        preferences = preferences,
        isGranted = { false },
        launchRequest = { systemPromptLaunches++ },
    )
    protected lateinit var library: LibraryManagementViewModel
    protected lateinit var backups: BackupsAndRestoreViewModel
    protected lateinit var restore: RestoreViewModel
    protected lateinit var onboarding: OnboardingViewModel
    protected lateinit var navController: NavHostController
    protected val persistedGrants = mutableSetOf<Uri>()
    protected val releasedGrants = mutableListOf<Uri>()
    protected val createdFiles = mutableListOf<File>()
    protected val workManager get() = WorkManager.getInstance(context)
    protected val music = "content://synthx.test.music/tree/primary%3AMusic".toUri()
    protected val arrivals = "content://synthx.test.arrivals/tree/primary%3AArrivals".toUri()

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

    /**
     * The dialog's confirm button stays disabled until the required word is typed exactly, so a
     * bare click on it is a silent no-op that never enqueues the restore. Once typed, the field's own text also reads the required word
     * and is itself clickable — exclude anything with a set-text action to isolate the button.
     */
    protected fun confirmRestoreWithTypedWord() {
        val requiredWord = context.getString(R.string.restore_confirm_action)
        composeRule.onNode(hasSetTextAction()).performTextInput(requiredWord)
        composeRule
            .onNode(hasText(requiredWord).and(hasClickAction()).and(!hasSetTextAction()))
            .assertIsEnabled()
            .performClick()
    }

    protected fun rationaleTitle() = context.getString(R.string.notification_permission_title)

    protected fun saveOnboardingMusic() {
        composeRule.runOnUiThread { library.saveOnboardingMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
    }

    /** Fresh install → pick the music folder → tap Done, leaving the wizard in/after ScanningStep. */
    protected fun startWizardAtDone() {
        startWizard()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        composeRule.onNodeWithText(context.getString(R.string.action_done)).assertIsEnabled().performClick()
    }

    /** Keeps [newArrivalsFolderNotice]'s WhileSubscribed upstream alive for the whole test. */
    protected fun keepNoticeHot(): kotlinx.coroutines.Job =
        CoroutineScope(Dispatchers.Default).launch { library.newArrivalsFolderNotice.collect { } }

    protected fun awaitState(id: UUID, state: WorkInfo.State) = runBlocking {
        withTimeout(10_000) {
            workManager.getWorkInfoByIdFlow(id).first { it?.state == state }
        }
    }

    /** A real scan (hanging stub) already running under the shared unique name. */
    internal fun startBlockingScan(behavior: ScanStub = ScanStub.Hang): UUID {
        ScanStubWorkerFactory.behavior = behavior
        val blocker = LibraryScanWorker.enqueue(context, ExistingWorkPolicy.REPLACE).id
        awaitState(blocker, WorkInfo.State.RUNNING)
        return blocker
    }

    protected fun textColorOf(text: String): androidx.compose.ui.graphics.Color {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    /** For state that arrives from WorkManager's own threads, which waitForIdle does not cover. */
    protected fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun emptyRepository(): SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        check(method.name == "getMissingSongCount") { "Unexpected repository access: ${method.name}" }
        flowOf(0)
    } as SongRepository

    protected fun saveMusic() {
        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(music) }
        await(library.musicFolderUri) { it == music }
    }

    protected fun configureAudioRoots() {
        saveMusic()
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(arrivals) }
        await(library.newArrivalsFolderUri) { it == arrivals }
    }

    protected fun scanIds() =
        workManager.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).get().map { it.id }

    protected fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean) = runBlocking {
        withTimeout(10_000) { flow.first(predicate) }
    }

    protected fun backup(uri: Uri) = BackupInfo(
        databaseUri = uri,
        metadataUri = null,
        fileName = "onboarding-preview.db",
        tier = BackupTier.Manual,
        timestamp = System.currentTimeMillis(),
        reason = null,
        sizeBytes = 0,
        restoreEligible = true,
    )

    protected fun validCandidateWithMetadata(): Pair<BackupInfo, File> {
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

    protected fun awaitRequest(): WorkInfo = runBlocking {
        withTimeout(10_000) {
            workManager.getWorkInfosForUniqueWorkFlow(RestoreWorker.UNIQUE_WORK_NAME)
                .first { infos -> infos.singleOrNull()?.state == WorkInfo.State.ENQUEUED }
                .single()
        }
    }

    @Composable
    protected fun WizardContent(factory: ViewModelProvider.Factory, startDestination: String) {
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

    protected fun wizardFactory(
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
    protected fun gatingStartDestination(): String =
        if (shouldShowOnboarding(preferences)) Screen.Onboarding.route else Screen.Home.route

    protected fun startWizard(
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
     */
    protected fun relaunchWizardAfterSimulatedProcessDeath() {
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
        val startDestination = gatingStartDestination()
        composeRule.runOnUiThread {
            composeRule.activity.setContent { WizardContent(factory, startDestination) }
        }
        composeRule.waitForIdle()
    }
}
