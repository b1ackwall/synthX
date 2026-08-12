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
import com.v1per.synthx.data.db.SynthXDatabase

/**
 * Runs a restore outside any UI lifecycle, mirroring [com.v1per.synthx.data.scanner.LibraryScanWorker]'s
 * shape — foreground service, unique work, [androidx.work.WorkInfo]-driven progress instead of a
 * ViewModel-owned [kotlinx.coroutines.flow.StateFlow].
 *
 * Unlike a scan, a restore overwrites the live database and cannot be undone in place, so every
 * check below fails closed: any uncertainty about the incoming backup aborts *before*
 * [BackupManager.restore] ever touches the live database file, in this fixed order —
 *  1. version check (cheapest — just the sidecar JSON already in memory)
 *  2. integrity check (reads the backup's bytes into a private staging file, read-only)
 *  3. safety backup of the *current* live state (only taken once 1–2 pass, so a doomed
 *     restore doesn't spend time/storage on a safety net it will never need)
 *  4. the actual swap ([BackupManager.restore], unchanged)
 *
 * Deliberately has no cancel action on its notification, unlike
 * [com.v1per.synthx.data.scanner.LibraryScanWorker]: [BackupManager.restore]'s file copy is a
 * plain blocking `copyTo` call that would not actually be interrupted mid-write by a
 * cancellation signal, so offering a cancel button here would suggest a safety valve that
 * cannot really stop a swap already underway.
 */
class RestoreWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val backup = inputData.toBackupInfo()
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing or invalid backup reference"))

        createNotificationChannel()
        setForeground(buildForegroundInfo(R.string.restore_notification_checking))

        val backupManager = BackupManager.create(applicationContext)

        // 1) Version check.
        val backupVersion = backupManager.readBackupDbVersion(backup)
        if (backupVersion == null) {
            return Result.failure(
                workDataOf(KEY_ERROR to applicationContext.getString(R.string.restore_error_version_unknown)),
            )
        }
        if (backupVersion != SynthXDatabase.VERSION) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR to applicationContext.getString(
                        R.string.restore_error_version_mismatch,
                        backupVersion,
                        SynthXDatabase.VERSION,
                    ),
                ),
            )
        }

        // 2) Integrity check — against a staged copy, never the live DB.
        val integrityResult = backupManager.checkIntegrity(backup)
        if (integrityResult.isFailure) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR to withDetail(
                        R.string.restore_error_integrity,
                        integrityResult.exceptionOrNull()?.message,
                    ),
                ),
            )
        }

        // 3) Safety backup of the current live state — the pre-existing net this restore
        // could otherwise destroy. If this fails, do not proceed with the swap.
        setForeground(buildForegroundInfo(R.string.restore_notification_safety_backup))
        val safetyResult = backupManager.createBackup(BackupTier.Auto, BackupReason.PRE_RESTORE_SAFETY)
        if (safetyResult.isFailure) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR to withDetail(
                        R.string.restore_error_safety_backup,
                        safetyResult.exceptionOrNull()?.message,
                    ),
                ),
            )
        }

        // 4) The actual swap — BackupManager.restore()'s existing logic, untouched.
        setForeground(buildForegroundInfo(R.string.restore_notification_restoring))
        val restoreResult = backupManager.restore(backup)
        return restoreResult.fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                Result.failure(
                    workDataOf(KEY_ERROR to withDetail(R.string.restore_error_restore_failed, e.message)),
                )
            },
        )
    }

    private fun withDetail(baseRes: Int, detail: String?): String {
        val base = applicationContext.getString(baseRes)
        return if (detail.isNullOrBlank()) base else "$base ($detail)"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(applicationContext.getString(R.string.restore_channel_name))
            .setDescription(applicationContext.getString(R.string.restore_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
    }

    private fun buildForegroundInfo(statusRes: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.restore_notification_title))
            .setContentText(applicationContext.getString(statusRes))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

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

        /**
         * Always [ExistingWorkPolicy.KEEP]: a second enqueue while a restore is already in
         * flight is a no-op — restoring twice concurrently would race two processes over the
         * same DB file, so unlike [com.v1per.synthx.data.scanner.LibraryScanWorker.enqueue]
         * this isn't exposed as a tunable parameter.
         */
        fun enqueue(context: Context, backup: BackupInfo) {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setInputData(backup.toInputData())
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun BackupInfo.toInputData(): Data = workDataOf(
            KEY_DATABASE_URI to databaseUri.toString(),
            KEY_METADATA_URI to metadataUri?.toString(),
            KEY_FILE_NAME to fileName,
            KEY_TIER to tier.name,
            KEY_TIMESTAMP to timestamp,
            KEY_REASON to reason,
            KEY_SIZE_BYTES to sizeBytes,
        )

        private fun Data.toBackupInfo(): BackupInfo? {
            val databaseUri = getString(KEY_DATABASE_URI)?.let { Uri.parse(it) } ?: return null
            val fileName = getString(KEY_FILE_NAME) ?: return null
            val tier = getString(KEY_TIER)?.let { name -> BackupTier.entries.find { it.name == name } }
                ?: return null
            val timestamp = getLong(KEY_TIMESTAMP, -1L).takeIf { it >= 0 } ?: return null
            val sizeBytes = getLong(KEY_SIZE_BYTES, -1L).takeIf { it >= 0 } ?: return null
            return BackupInfo(
                databaseUri = databaseUri,
                metadataUri = getString(KEY_METADATA_URI)?.let { Uri.parse(it) },
                fileName = fileName,
                tier = tier,
                timestamp = timestamp,
                reason = getString(KEY_REASON),
                sizeBytes = sizeBytes,
            )
        }
    }
}
