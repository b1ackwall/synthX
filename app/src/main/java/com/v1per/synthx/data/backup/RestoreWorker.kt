package com.v1per.synthx.data.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.v1per.synthx.R

/** WorkManager orchestrates restore; RestoreTransaction owns its crash-safe filesystem work. */
class RestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (RestoreStartup.result() != RestoreRecovery.NORMAL) {
            return Result.failure(workDataOf(KEY_ERROR to "Existing restore recovery is not complete"))
        }
        runCatching { RestoreCoordinator.requireNormal() }.getOrElse {
            return Result.failure(workDataOf(KEY_ERROR to "Restore requires synthX to be reopened"))
        }
        runCatching { DatabaseStartupGate.awaitReady() }.getOrElse { error ->
            return Result.failure(
                workDataOf(KEY_ERROR to (error.message ?: "Database startup maintenance failed"))
            )
        }
        val backup = inputData.toBackupInfo()
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing or invalid backup reference"))
        createNotificationChannel()
        setForeground(buildForegroundInfo(R.string.restore_notification_checking))
        setForeground(buildForegroundInfo(R.string.restore_notification_restoring))
        return BackupManager.create(applicationContext).restore(backup).fold(
            onSuccess = { Result.success() },
            onFailure = { error -> Result.failure(workDataOf(KEY_ERROR to error.message.orEmpty())) },
        )
    }

    private fun createNotificationChannel() {
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.restore_channel_name))
                .setDescription(applicationContext.getString(R.string.restore_channel_description))
                .setShowBadge(false).build(),
        )
    }
    private fun buildForegroundInfo(status: Int): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.restore_notification_title))
            .setContentText(applicationContext.getString(status))
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setSilent(true)
            .setProgress(0, 0, true).build(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
    companion object {
        const val UNIQUE_WORK_NAME = "db_restore"
        const val KEY_ERROR = "error"
        private const val KEY_DATABASE_URI = "databaseUri"
        private const val KEY_METADATA_URI = "metadataUri"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_TIER = "tier"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_REASON = "reason"
        private const val KEY_SIZE_BYTES = "sizeBytes"
        private const val CHANNEL_ID = "db_restore"
        private const val NOTIFICATION_ID = 1002
        fun enqueue(context: Context, backup: BackupInfo) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP,
                request(backup))
        }
        internal fun request(backup: BackupInfo) =
            OneTimeWorkRequestBuilder<RestoreWorker>().setInputData(backup.toInputData())
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build()).build()

        internal fun BackupInfo.toInputData(): Data = workDataOf(
            KEY_DATABASE_URI to databaseUri.toString(), KEY_METADATA_URI to metadataUri?.toString(),
            KEY_FILE_NAME to fileName, KEY_TIER to tier.name, KEY_TIMESTAMP to timestamp,
            KEY_REASON to reason, KEY_SIZE_BYTES to sizeBytes,
        )
        internal fun Data.toBackupInfo(): BackupInfo? {
            val uri = getString(KEY_DATABASE_URI)?.let(Uri::parse) ?: return null
            val tier = getString(KEY_TIER)?.let { n -> BackupTier.entries.find { it.name == n } } ?: return null
            return BackupInfo(uri, getString(KEY_METADATA_URI)?.let(Uri::parse), getString(KEY_FILE_NAME) ?: return null,
                tier, getLong(KEY_TIMESTAMP, -1).takeIf { it >= 0 } ?: return null,
                getString(KEY_REASON), getLong(KEY_SIZE_BYTES, -1).takeIf { it >= 0 } ?: return null)
        }
    }
}
