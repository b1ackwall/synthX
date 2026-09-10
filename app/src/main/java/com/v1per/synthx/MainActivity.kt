package com.v1per.synthx

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.MigrationInvariantFailedException
import com.v1per.synthx.data.backup.MigrationRecoveryAction
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.UnsupportedLiveDatabaseVersionException
import com.v1per.synthx.data.playlistart.PlaylistArtBackfill
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.IssuesScanWorker
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.data.scanner.VerifyLibraryWorker
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    private var databaseStartupAttemptRunning = false

    /**
     * Resolved here rather than only inside MainScaffold so the queue restore can start
     * before first composition. ViewModelProvider with the default key returns the very
     * same instance `viewModel<PlaybackViewModel>()` picks up in the Compose tree — this
     * activity is the store owner for both.
     */
    private val playbackViewModel: PlaybackViewModel by lazy {
        ViewModelProvider(this, PlaybackViewModel.Factory(applicationContext))[
            PlaybackViewModel::class.java
        ]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolveDatabaseStartup()
    }

    /**
     * No production Compose tree is installed until the process-wide database gate resolves.
     * Failure content owns no repository/DAO and offers a later retry while Room stays closed.
     */
    private fun resolveDatabaseStartup() {
        if (databaseStartupAttemptRunning) return
        databaseStartupAttemptRunning = true
        lifecycleScope.launch {
            val startupFailure = runCatching { DatabaseStartupGate.awaitReady() }.exceptionOrNull()
            // Synchronous SharedPreferences read, not a Room/DAO read — safe ahead of the
            // database gate resolving. Onboarding shows for a genuine fresh install, a
            // post-uninstall reinstall, or cleared app data alike: all three collapse to this
            // one condition.
            val startDestination = if (PreferencesManager(applicationContext).musicFolderUri == null) {
                Screen.Onboarding.route
            } else {
                Screen.Home.route
            }
            enableEdgeToEdge()
            setContent {
                SynthXTheme {
                    when {
                        RestoreCoordinator.current() != RestoreCoordinator.Terminal.NORMAL ->
                            RestoreTerminalContent(RestoreCoordinator.current())
                        startupFailure is MigrationInvariantFailedException ->
                            MigrationRecoveryContent(applicationContext = applicationContext)
                        startupFailure != null -> DatabaseStartupFailureContent(
                            error = startupFailure,
                            onRetry = ::resolveDatabaseStartup,
                        )
                        else -> {
                            NotificationPermissionEffect()
                            MainScaffold(startDestination = startDestination)
                        }
                    }
                }
            }
            if (startupFailure == null &&
                RestoreCoordinator.current() == RestoreCoordinator.Terminal.NORMAL
            ) {
                pruneFinishedWork()
                runLaunchMaintenance()
            }
            databaseStartupAttemptRunning = false
        }
    }

    /**
     * The app is single-Activity, so this is also "the app was backgrounded" — the point
     * at which the resume position has to be durable, since the process can be killed
     * from here without further warning. Chosen over adding lifecycle-process just for
     * ProcessLifecycleOwner, which would observe the same edge in this app.
     */
    override fun onStop() {
        super.onStop()
        if (DatabaseStartupGate.isReady() &&
            RestoreCoordinator.current() == RestoreCoordinator.Terminal.NORMAL
        ) {
            playbackViewModel.persistPlaybackState()
        }
    }

    /**
     * WorkManager keeps a unique work item's terminal WorkInfo (SUCCEEDED/FAILED/CANCELLED)
     * around until it's explicitly pruned or the unique name is enqueued again — without this,
     * RestoreViewModel reading RestoreWorker.UNIQUE_WORK_NAME's WorkInfo on a later, unrelated
     * visit to the Restore screen would still see last session's SUCCEEDED state and resurface
     * the "restore complete, reopen synthX" dialog. WorkManager has no per-unique-work prune,
     * only this database-wide one — harmless here since nothing else in the app persists across
     * restarts by reading a finished WorkInfo the way Restore does.
     */
    private fun pruneFinishedWork() {
        WorkManager.getInstance(applicationContext).pruneWork()
    }

    /** Runs only after C1 has granted process-wide Room permission. */
    private fun runLaunchMaintenance() {
        val preferences = PreferencesManager(applicationContext)
        val backupManager = BackupManager.create(applicationContext)
        // Resolved on the main thread before the coroutine: ViewModel creation (and the
        // MediaController binding it starts) belongs there, unlike the call below.
        val playback = playbackViewModel

        // P7.2: purely informational, never blocks — see BackupFolderLaunchCheck's kdoc for
        // why this can't reuse DatabaseStartupGate, and how it stays a once-per-process check
        // across Activity recreation despite this function re-running on every one of those.
        BackupFolderLaunchCheck.runOnce(applicationContext, lifecycleScope)

        lifecycleScope.launch(Dispatchers.IO) {
            backupDailyIfDue(preferences, backupManager)
            scanIssuesDailyIfDue(preferences)
            // Ahead of verifyLibrary so the mini player comes back without waiting on a full
            // existence sweep — a song that vanished since last session fails on play with
            // the normal error snackbar.
            playback.restoreSavedQueue()
            runArtSweepIfPending(preferences)
            backfillPlaylistArt()
            // Nothing to scan yet if onboarding hasn't configured a root folder — running this
            // chain here would be pointless, and pending Onboarding's own saveMusicFolder call
            // to enqueue LibraryScanWorker directly instead, this way it can't race it either.
            if (preferences.musicFolderUri != null) {
                runLaunchScans()
            }
        }
    }

    /**
     * B1/B3: consumes [PreferencesManager.artSweepPending] — set by RestoreTransaction on the
     * cold boot right after a successful restore — and, if it was set, runs [ArtRecovery]'s
     * full-library sweep silently in the background. Cleared before the sweep runs (not after)
     * so a process death mid-sweep can't re-trigger it forever; a missed sweep is not silent
     * data loss, it just leaves whatever art it would have fixed for the Issues screen's manual
     * Fix action to pick up instead. No progress UI — unlike the manual Fix action, this pass
     * has no screen open to report to.
     */
    private suspend fun runArtSweepIfPending(preferences: PreferencesManager) {
        if (!preferences.artSweepPending) return
        preferences.artSweepPending = false
        runCatching {
            val result = SongRepositoryImpl.create(applicationContext).recoverMissingArt()
            Log.d(
                TAG,
                "Post-restore art sweep complete — checked=${result.checked} " +
                    "recovered=${result.recovered} skipped=${result.skipped}",
            )
        }.onFailure { e ->
            Log.w(TAG, "Post-restore art sweep failed: ${e.message}", e)
        }
    }

    /**
     * Gives any playlist still missing its generated cover art (rows that predate the v13->v14
     * schema, plus anything PlaylistSeedBootstrapper inserted) a hue and structure seed. Placed
     * after the queue restore for the same reason the art sweep is: the mini player coming back
     * matters more than a cover that is currently drawing the flat placeholder, and the playlist
     * lists redraw on their own the moment the rows land, since they observe Room.
     *
     * Unconditional every launch and a single SELECT when there is nothing to do — see
     * [PlaylistArtBackfill]. A failure here is cosmetic, never data loss, so it is logged and
     * the rest of launch maintenance continues.
     */
    private suspend fun backfillPlaylistArt() {
        runCatching { PlaylistArtBackfill.run(applicationContext) }
            .onSuccess { assigned ->
                if (assigned > 0) Log.d(TAG, "Playlist art backfill assigned $assigned playlists")
            }
            .onFailure { e -> Log.w(TAG, "Playlist art backfill failed: ${e.message}", e) }
    }

    /**
     * Two launch-time passes, chained so they run in order under LibraryScanWorker's unique
     * work name:
     *
     *  1. [VerifyLibraryWorker] — cheap existence sweep flagging songs whose files have gone.
     *     The full main-root scan stays user-triggered from Library Management.
     *  2. [NewArrivalsScanWorker] — full walk of the (small) new-arrivals tree, ingesting
     *     whatever the  companion app dropped there since last launch.
     *
     * Sharing the unique name is what keeps either of them from racing a manually-triggered
     * Rescan over `is_missing`. [ExistingWorkPolicy.KEEP] on the chain head preserves the
     * previous behaviour: a re-trigger (including the one that comes with every Activity
     * recreation) is a no-op while the chain is still running or queued.
     */
    private fun runLaunchScans() {
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                LibraryScanWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                VerifyLibraryWorker.request(),
            )
            .then(NewArrivalsScanWorker.request())
            .enqueue()
    }

    private suspend fun backupDailyIfDue(
        preferences: PreferencesManager,
        backupManager: BackupManager,
    ) {
        val last = preferences.lastDailyBackupTimestamp
        val now = System.currentTimeMillis()
        if (last != 0L && now - last < DAILY_INTERVAL_MS) return

        backupManager.createBackup(BackupTier.Daily)
            .onSuccess { preferences.lastDailyBackupTimestamp = now }
            .onFailure { e -> Log.w(TAG, "Daily backup skipped: ${e.message}") }

        // P3.2: piggybacks on this same once-per-day gate rather than a second, uncoordinated
        // timer — re-validates the most recent successful backup file itself (not just today's
        // write), catching corruption a healthy write at the time wouldn't have caught.
        backupManager.verifyMostRecentBackup()
            .onFailure { e -> Log.w(TAG, "Backup verification failed: ${e.message}") }
    }

    /**
     * The Issues screen's counterpart to [backupDailyIfDue], deliberately the same shape: one
     * persisted last-run timestamp compared against the same [DAILY_INTERVAL_MS], with a
     * never-run value (0) always counting as due.
     *
     * Differs in one respect only — the work is handed to WorkManager rather than done inline.
     * A whole-library art sweep is far too long to hold up launch maintenance, and routing it
     * through [IssuesScanWorker.UNIQUE_WORK_NAME] is also what lets the Issues screen observe
     * this automatic run and honestly report "Scanning…" if the user happens to open it while
     * this one is still going. [IssuesScanWorker] writes the timestamp itself, on completion,
     * so an interrupted or failed run stays due rather than being marked done by the enqueue.
     */
    private fun scanIssuesDailyIfDue(preferences: PreferencesManager) {
        val last = preferences.lastIssuesScanTimestamp
        val now = System.currentTimeMillis()
        if (last != 0L && now - last < DAILY_INTERVAL_MS) return

        IssuesScanWorker.enqueue(applicationContext)
    }

    private companion object {
        const val TAG = "SynthXScan"
        const val DAILY_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

@Composable
private fun DatabaseStartupFailureContent(
    error: Throwable,
    onRetry: () -> Unit,
) {
    val message = if (error is UnsupportedLiveDatabaseVersionException) {
        "This database was created by a newer synthX version. Downgrade is disabled, and the " +
            "database remains unopened. Install a compatible synthX version to continue."
    } else {
        "synthX could not complete the required database safety maintenance. The database " +
            "remains unopened.\n\n${error.message.orEmpty()}"
    }
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Database startup paused") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) { Text("Retry") }
            },
        )
    }
}

/**
 * Shown when a migration ran but failed [com.v1per.synthx.data.backup.MigrationInvariantCheck]
 * (N6 + P2.3's locked recovery-offer decision). Unlike [DatabaseStartupFailureContent]'s bare
 * Retry — which would just re-hit the same pending-recovery flag forever, see
 * [DatabaseStartupController.hasPendingMigrationRecovery] — this offers the one action that can
 * actually resolve it: restoring the internal pre-migration snapshot taken just before the
 * migration ran, then restarting so the whole attempt can be retried from a clean process.
 */
@Composable
private fun MigrationRecoveryContent(applicationContext: android.content.Context) {
    val scope = rememberCoroutineScope()
    var isRestoring by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<String?>(null) }

    val message = "synthX updated its database, but a post-migration safety check failed. " +
        "Normal use is disabled to protect your library.\n\n" +
        "Your library can be restored to its state from just before this update attempt. " +
        "After restoring, close and reopen synthX to try again." +
        (restoreError?.let { "\n\nLast attempt failed: $it" } ?: "")

    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Database update needs recovery") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    enabled = !isRestoring,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                    onClick = {
                        isRestoring = true
                        scope.launch(Dispatchers.IO) {
                            MigrationRecoveryAction.restoreFromInternalSnapshot(applicationContext)
                                .onSuccess { exitProcess(0) }
                                .onFailure { error ->
                                    restoreError = error.message ?: error.toString()
                                    isRestoring = false
                                }
                        }
                    },
                ) { Text(if (isRestoring) "Restoring…" else "Restore and restart") }
            },
        )
    }
}

@Composable
private fun RestoreTerminalContent(state: RestoreCoordinator.Terminal) {
    val diagnosticId = RestoreCoordinator.diagnosticOperationId.value ?: "unavailable"
    val message = when (state) {
        RestoreCoordinator.Terminal.RESTART_REQUIRED -> "Restore complete. Close and reopen synthX to continue."
        RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED -> "Restore was rolled back safely. Close and reopen synthX to continue."
        RestoreCoordinator.Terminal.EMERGENCY ->
            "Automatic database recovery could not validate a usable database.\n\n" +
                "Normal app use is disabled to protect your data.\n\n" +
                "Diagnostic operation ID:\n$diagnosticId"
        RestoreCoordinator.Terminal.NORMAL -> ""
    }
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AlertDialog(onDismissRequest = {}, title = {
            Text(if (state == RestoreCoordinator.Terminal.EMERGENCY) "Database recovery required" else "Restart required")
        }, text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = { exitProcess(0) },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) { Text("Close") }
            })
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun NotificationPermissionEffect() {
    val permissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
        }
    }
}
