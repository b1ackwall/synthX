package com.v1per.synthx

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.VerifyLibraryWorker
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        runLaunchMaintenance()
        enableEdgeToEdge()
        setContent {
            SynthXTheme {
                NotificationPermissionEffect()
                MainScaffold()
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
        playbackViewModel.persistPlaybackState()
    }

    /**
     * Ordered deliberately: the pre-migration snapshot must be taken before anything
     * opens the Room database, because opening it is what runs the migration.
     * BackupManager checkpoints through a raw SQLite handle for exactly this reason.
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
            verifyLibrary()
        }
    }

    // Cheap pass: flags songs whose files have gone. The full scan stays user-triggered
    // from Library Management. Routed through WorkManager under LibraryScanWorker's unique
    // work name (KEEP) rather than run inline, so it can never race a manually-triggered
    // Rescan reading/writing is_missing at the same time — whichever is already running
    // wins, the other enqueue is a no-op.
    private fun verifyLibrary() {
        VerifyLibraryWorker.enqueue(applicationContext)
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
