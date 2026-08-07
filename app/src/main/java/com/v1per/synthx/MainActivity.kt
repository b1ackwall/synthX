package com.v1per.synthx

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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
     * Ordered deliberately: the pre-migration snapshot must be taken before anything
     * opens the Room database, because opening it is what runs the migration.
     * BackupManager checkpoints through a raw SQLite handle for exactly this reason.
     */
    private fun runLaunchMaintenance() {
        val preferences = PreferencesManager(applicationContext)
        val backupManager = BackupManager.create(applicationContext)

        lifecycleScope.launch(Dispatchers.IO) {
            backupBeforeMigration(preferences, backupManager)
            backupDailyIfDue(preferences, backupManager)
            verifyLibrary()
        }
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

    // Cheap pass: flags songs whose files have gone. The full scan stays user-triggered
    // from Library Management.
    private suspend fun verifyLibrary() {
        try {
            val newlyMissing = SongRepositoryImpl.create(applicationContext).verifyLibrary()
            Log.d(TAG, "Launch verify complete: $newlyMissing newly missing")
        } catch (e: Exception) {
            Log.e(TAG, "Launch verify failed", e)
        }
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
