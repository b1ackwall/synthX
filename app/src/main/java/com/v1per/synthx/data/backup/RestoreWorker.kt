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
import com.v1per.synthx.preferences.PreferencesManager
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                workDataOf(KEY_ERROR to (error.message ?: "Database startup maintenance failed")),
            )
        }
        val backup = inputData.toBackupInfo()
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing or invalid backup reference"))
        createNotificationChannel()
        setForeground(buildForegroundInfo(RestoreProgressStep.COPYING))
        return BackupManager.create(applicationContext).restore(backup, id.toString()) { step ->
            // Coarse phase progress — see RestoreProgressStep's doc
            // for why this is a small separate enum rather than the durable journal Phase.
            setProgress(workDataOf(KEY_PROGRESS_STEP to step.name))
            setForeground(buildForegroundInfo(step))
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                Result.failure(
                    workDataOf(
                        KEY_ERROR to RestoreFailureFormatter.describe(
                            error,
                            RestoreCoordinator.diagnosticOperationId.value,
                        ),
                    ),
                )
            },
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

    /**
     * Determinate over 4 fixed steps (see [RestoreProgressStep])
     * rather than indeterminate — mirrors LibraryScanWorker's own foreground notification,
     * including its cancel action via [WorkManager.createCancelPendingIntent]. Cancelling only
     * takes effect while the worker is still in the cancellable COPYING/VALIDATING window (see
     * RestoreTransaction.execute's NonCancellable section) — a tap during INSTALLING/FINISHING
     * is a no-op until the (uninterruptible) live-swap finishes on its own.
     */
    private fun buildForegroundInfo(step: RestoreProgressStep): ForegroundInfo {
        val stepIndex = RestoreProgressStep.entries.indexOf(step) + 1
        val totalSteps = RestoreProgressStep.entries.size
        val status = when (step) {
            RestoreProgressStep.COPYING -> R.string.restore_notification_checking
            RestoreProgressStep.VALIDATING -> R.string.restore_notification_validating
            RestoreProgressStep.INSTALLING -> R.string.restore_notification_safety_backup
            RestoreProgressStep.FINISHING -> R.string.restore_notification_restoring
        }
        return ForegroundInfo(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(applicationContext.getString(R.string.restore_notification_title))
                .setContentText(applicationContext.getString(status))
                .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setSilent(true)
                .setProgress(totalSteps, stepIndex, false)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    applicationContext.getString(R.string.action_cancel),
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                )
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
    companion object {
        const val UNIQUE_WORK_NAME = "db_restore"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_STEP = "progressStep"
        private const val KEY_DATABASE_URI = "databaseUri"
        private const val KEY_METADATA_URI = "metadataUri"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_TIER = "tier"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_REASON = "reason"
        private const val KEY_SIZE_BYTES = "sizeBytes"
        private const val CHANNEL_ID = "db_restore"
        private const val NOTIFICATION_ID = 1002
        suspend fun enqueue(context: Context, backup: BackupInfo): UUID {
            val workManager = WorkManager.getInstance(context)
            val requested = request(backup)
            val ownerBeforeEnqueue = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
                    .singleOrNull { !it.state.isFinished }
                    ?.id
            }
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                requested,
            ).awaitCompletion()

            val infos = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            }
            val owner = infos.firstOrNull { it.id == requested.id }
                ?: infos.singleOrNull { !it.state.isFinished }
                ?: ownerBeforeEnqueue?.let { priorId -> infos.firstOrNull { it.id == priorId } }
                ?: error("Could not resolve the WorkManager request owning $UNIQUE_WORK_NAME")
            check(
                PreferencesManager(context.applicationContext).trackRestoreRequestDurably(
                    owner.id.toString(),
                    System.currentTimeMillis(),
                ),
            ) { "Could not persist restore request identity" }
            RestoreRequestTracking.changed()
            return owner.id
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        internal fun request(backup: BackupInfo) =
            OneTimeWorkRequestBuilder<RestoreWorker>().setInputData(backup.toInputData())
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build()).build()

        internal fun BackupInfo.toInputData(): Data = workDataOf(
            KEY_DATABASE_URI to databaseUri.toString(),
            KEY_METADATA_URI to metadataUri?.toString(),
            KEY_FILE_NAME to fileName,
            KEY_TIER to tier.name,
            KEY_TIMESTAMP to timestamp,
            KEY_REASON to reason,
            KEY_SIZE_BYTES to sizeBytes,
        )
        internal fun Data.toBackupInfo(): BackupInfo? {
            val uri = getString(KEY_DATABASE_URI)?.let(Uri::parse) ?: return null
            val tier = getString(KEY_TIER)?.let { n -> BackupTier.entries.find { it.name == n } } ?: return null
            return BackupInfo(
                uri,
                getString(KEY_METADATA_URI)?.let(Uri::parse),
                getString(KEY_FILE_NAME) ?: return null,
                tier,
                getLong(KEY_TIMESTAMP, -1).takeIf { it >= 0 } ?: return null,
                getString(KEY_REASON),
                getLong(KEY_SIZE_BYTES, -1).takeIf { it >= 0 } ?: return null,
            )
        }

        private suspend fun androidx.work.Operation.awaitCompletion() {
            suspendCancellableCoroutine { continuation ->
                result.addListener(
                    {
                        runCatching { result.get() }
                            .onSuccess { if (continuation.isActive) continuation.resume(Unit) }
                            .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
                    },
                    Executor(Runnable::run),
                )
            }
        }
    }
}
