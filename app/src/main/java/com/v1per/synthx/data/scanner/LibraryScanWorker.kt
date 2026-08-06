package com.v1per.synthx.data.scanner

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.v1per.synthx.R
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

        createNotificationChannel()
        setForeground(buildForegroundInfo(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE))

        val repository = SongRepositoryImpl.create(applicationContext)

        // Both setProgress and the notification update cross a process boundary, so they
        // are throttled to whole-percent changes — a 5,000-file library would otherwise
        // issue 10,000 IPC round-trips it gains nothing from.
        var lastPercent = Int.MIN_VALUE

        return try {
            val result = repository.scanLibrary(uri) { current, total ->
                val percent = if (total > 0) current * 100 / total else PROGRESS_INDETERMINATE
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgress(workDataOf(KEY_CURRENT to current, KEY_TOTAL to total))
                    setForeground(buildForegroundInfo(current, total))
                }
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
                OneTimeWorkRequestBuilder<LibraryScanWorker>().build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
