package com.v1per.synthx

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
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
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.data.scanner.VerifyLibraryWorker
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

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
        // The application starts recovery off-main. Do not instantiate playback, Room, or
        // launch maintenance until it has made the database namespace safe to open.
        lifecycleScope.launch(Dispatchers.IO) {
            RestoreStartup.await()
            launch(Dispatchers.Main) {
                enableEdgeToEdge()
                setContent {
                    SynthXTheme {
                        if (RestoreCoordinator.current() == RestoreCoordinator.Terminal.NORMAL) {
                            NotificationPermissionEffect()
                            MainScaffold()
                        } else {
                            RestoreTerminalContent(RestoreCoordinator.current())
                        }
                    }
                }
                if (RestoreCoordinator.current() == RestoreCoordinator.Terminal.NORMAL) {
                    pruneFinishedWork()
                    runLaunchMaintenance()
                }
            }
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
        if (RestoreCoordinator.current() == RestoreCoordinator.Terminal.NORMAL) {
            playbackViewModel.persistPlaybackState()
        }
    }

    /**
     * WorkManager keeps a unique work item's terminal WorkInfo (SUCCEEDED/FAILED/CANCELLED)
     * around until it's explicitly pruned or the unique name is enqueued again — without this,
     * RestoreViewModel reading RestoreWorker.UNIQUE_WORK_NAME's WorkInfo on a later, unrelated
     * visit to the Restore screen would still see last session's SUCCEEDED state and resurface
     * the "restore complete, reopen synthX" dialog. Called before setContent, ahead of any
     * ViewModel that might read WorkInfo. WorkManager has no per-unique-work prune, only this
     * database-wide one — harmless here since nothing else in the app persists across restarts
     * by reading a finished WorkInfo the way Restore does.
     */
    private fun pruneFinishedWork() {
        WorkManager.getInstance(applicationContext).pruneWork()
    }

    /**
     * Ordered deliberately: the pre-migration snapshot must be taken before anything
     * opens the Room database, because opening it is what runs the migration.
     * BackupManager snapshots through a raw SQLite handle for exactly this reason.
     */
    private fun runLaunchMaintenance() {
        val preferences = PreferencesManager(applicationContext)
        val backupManager = BackupManager.create(applicationContext)
        // Resolved on the main thread before the coroutine: ViewModel creation (and the
        // MediaController binding it starts) belongs there, unlike the call below.
        val playback = playbackViewModel

        lifecycleScope.launch(Dispatchers.IO) {
            backupBeforeMigration(preferences, backupManager)
            backupDailyIfDue(preferences, backupManager)
            // Sits inside this chain, not beside it: the restore queries Room, and a query
            // is what triggers the migration the snapshot above has to precede. Ahead of
            // verifyLibrary so the mini player comes back without waiting on a full
            // existence sweep — a song that vanished since last session fails on play with
            // the normal error snackbar.
            playback.restoreSavedQueue()
            runLaunchScans()
        }
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

    private suspend fun backupBeforeMigration(
        preferences: PreferencesManager,
        backupManager: BackupManager,
    ) {
        val lastKnown = preferences.lastKnownDbVersion
        // 0 means fresh install — there is no prior schema and nothing worth snapshotting.
        if (lastKnown != 0 && lastKnown != SynthXDatabase.VERSION) {
            Log.i(TAG, "Schema $lastKnown -> ${SynthXDatabase.VERSION}, backing up first")
            backupManager.createBackup(BackupTier.Auto, BackupReason.MIGRATION)
                .onFailure { e -> Log.w(TAG, "Pre-migration backup skipped: ${e.message}") }
        }
        preferences.lastKnownDbVersion = SynthXDatabase.VERSION
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
    }

    private companion object {
        const val TAG = "SynthXScan"
        const val DAILY_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

@Composable
private fun RestoreTerminalContent(state: RestoreCoordinator.Terminal) {
    val message = when (state) {
        RestoreCoordinator.Terminal.RESTART_REQUIRED -> "Restore complete. Close and reopen synthX to continue."
        RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED -> "Restore was rolled back safely. Close and reopen synthX to continue."
        RestoreCoordinator.Terminal.EMERGENCY -> "Automatic recovery could not validate a usable local database. Normal app use is disabled."
        RestoreCoordinator.Terminal.NORMAL -> ""
    }
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AlertDialog(onDismissRequest = {}, title = { Text("Restart required") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { exitProcess(0) }) { Text("Close") } })
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
