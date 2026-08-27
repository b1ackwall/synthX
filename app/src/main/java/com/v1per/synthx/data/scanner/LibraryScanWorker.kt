package com.v1per.synthx.data.scanner

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.WorkerDatabaseAccess
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException

/**
 * Runs the library scan outside any UI lifecycle.
 *
 * The folder URI is read from preferences at execution time rather than passed as input
 * data, so a replaced scan always picks up the current folder.
 */
class LibraryScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // WorkManager can launch this process without an Activity. Recovery must settle before
        // this worker admits a DB operation or creates repository/Room objects.
        val preferences = PreferencesManager(applicationContext)
        val uriString = preferences.musicFolderUri
            ?: return Result.failure(workDataOf(KEY_ERROR to "No folder selected"))
        val uri = Uri.parse(uriString)

        // A stored URI outlives its grant (revoked access, restored backup). Scanning
        // without the grant reports a successful empty scan and flags the whole library
        // missing, so drop the stale URI and prompt for a re-pick instead.
        val grantValid = applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!grantValid) {
            preferences.musicFolderUri = null
            return Result.failure(workDataOf(KEY_ERROR to GRANT_REVOKED_MESSAGE))
        }

        // This ordinary pre-rescan backup obtains live Room. Await C1 before attempting it;
        // a Worker-started process must not reach this backup's Room branch ahead of migration
        // safety. The shared WorkerDatabaseAccess below observes the same already-ready gate.
        runCatching { DatabaseStartupGate.awaitReady() }.getOrElse { error ->
            return Result.failure(
                workDataOf(KEY_ERROR to (error.message ?: "Database startup maintenance failed"))
            )
        }

        // Snapshot before the scan mutates anything. cancelActiveScan = false is required:
        // this worker *is* the active scan, so the usual await-terminal-state guard would
        // wait on itself forever.
        BackupManager.create(applicationContext).createBackup(
            tier = BackupTier.Auto,
            reason = BackupReason.RESCAN,
            cancelActiveScan = false,
        )

        createNotificationChannel()
        setForeground(buildForegroundInfo(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE))

        // Both setProgress and the notification update cross a process boundary, so they
        // are throttled to whole-percent changes — a 5,000-file library would otherwise
        // issue 10,000 IPC round-trips it gains nothing from.
        var lastPercent = Int.MIN_VALUE

        // Every *other* configured audio root. Their files have to count as found or this
        // scan flags every song that lives only under one of them as missing. The primary
        // root is excluded because the walk below already covers it.
        val otherRoots = preferences.configuredAudioRootUris().filter { it != uri }

        return try {
            val result = WorkerDatabaseAccess.run {
                val repository = SongRepositoryImpl.create(applicationContext)
                repository.scanLibraryAlreadyAdmitted(uri, otherRootUris = otherRoots) { current, total ->
                val percent = if (total > 0) current * 100 / total else PROGRESS_INDETERMINATE
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgress(workDataOf(KEY_CURRENT to current, KEY_TOTAL to total))
                    setForeground(buildForegroundInfo(current, total))
                }
                }
            }
            if (!result.missingReconciled) {
                // Not a scan failure — added/updated rows are all committed. Only the
                // is_missing sweep was skipped, because a configured root couldn't be
                // enumerated and reconciling against a short path set would have wrongly
                // flagged every song under it. FolderScanner logs which root and why.
                Log.w(TAG, "Scan completed without missing-detection — a configured root was unreachable")
            }
            Result.success(
                workDataOf(
                    KEY_ADDED to result.added,
                    KEY_UPDATED to result.updated,
                    KEY_MISSING to result.missing,
                    KEY_UNCHANGED to result.unchanged,
                )
            )
        } catch (e: CancellationException) {
            // Let WorkManager see the cancellation — the Room transaction has already
            // rolled back, so the library is untouched.
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Scan failed")))
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(applicationContext.getString(R.string.scan_channel_name))
            .setDescription(applicationContext.getString(R.string.scan_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
    }

    private fun buildForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val determinate = total > 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.scan_notification_title))
            .setContentText(
                if (determinate) {
                    applicationContext.getString(R.string.scan_notification_progress, current, total)
                } else {
                    applicationContext.getString(R.string.scan_notification_preparing)
                }
            )
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(if (determinate) total else 0, current, !determinate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.action_cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private const val TAG = "SynthXScan"

        const val UNIQUE_WORK_NAME = "library_scan"

        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_ADDED = "added"
        const val KEY_UPDATED = "updated"
        const val KEY_MISSING = "missing"
        const val KEY_UNCHANGED = "unchanged"
        const val KEY_ERROR = "error"

        const val GRANT_REVOKED_MESSAGE =
            "Access to your music folder has been revoked. Please select it again."

        private const val CHANNEL_ID = "library_scan"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_INDETERMINATE = FolderScanner.PROGRESS_INDETERMINATE

        /**
         * [ExistingWorkPolicy.KEEP] is the right default: a second enqueue while a scan is
         * already running is a no-op. Pass [ExistingWorkPolicy.REPLACE] only when the
         * folder itself changed, since an in-flight scan is then scanning the wrong tree.
         */
        fun enqueue(
            context: Context,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                // No Constraints: pure local SAF file I/O, no network dependency — nothing
                // here to gate on connectivity, battery, or storage state.
                OneTimeWorkRequestBuilder<LibraryScanWorker>()
                    .setConstraints(Constraints.NONE)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
