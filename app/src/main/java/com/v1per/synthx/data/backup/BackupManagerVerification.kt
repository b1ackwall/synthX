package com.v1per.synthx.data.backup

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

// BackupManager's paths that stage a published backup locally and inspect it.

/**
 * Split out from [verifyMostRecentBackup] so tests can supply a [BackupListResult] directly
 * — real [BackupInfo] entries built with `file://` URIs and a genuine SQLite backup file —
 * the same technique [classifyBackupList] uses to avoid needing a genuine SAF tree grant.
 */
internal suspend fun BackupManager.verifyBackupList(listing: BackupListResult): Result<Unit> =
    withContext(Dispatchers.IO) {
        val eligible = listing.backupsOrEmpty().firstOrNull { it.restoreEligible }

        if (eligible == null) {
            BackupOutcomeStore.recordVerificationSkipped(context)
            return@withContext Result.success(Unit)
        }

        val result = runCatching {
            val metadata = requireNotNull(readRestoreMetadata(eligible)) {
                "Eligible backup is missing valid metadata"
            }
            val stage = BackupStagingCoordinator.acquire(context, "verify")
            val copy = stage.file
            try {
                val digest = RestoreTransaction.copySafOnce(context, eligible.databaseUri.toString(), copy)
                require(digest.sizeBytes == metadata.sizeBytes && digest.sha256 == metadata.sha256) {
                    "Backup bytes do not match its declared size and SHA-256 metadata"
                }
                val validated = BackupSnapshotValidator.validate(copy, migrationSnapshot = true)
                require(validated.schema.userVersion == metadata.dbVersion) {
                    "Backup metadata version does not match the database"
                }
            } finally {
                stage.close()
            }
            Unit
        }
        BackupOutcomeStore.recordVerificationOutcome(context, result)
        result
    }

internal suspend fun BackupManager.previewBackupInternal(
    backup: BackupInfo,
    onProgress: (RestoreProgressStep) -> Unit,
): Result<BackupPreviewCounts> = withContext(Dispatchers.IO) {
    runCatching {
        val stage = BackupStagingCoordinator.acquire(context, "preview")
        val copy = stage.file
        try {
            // Preview never touches the live database, so liveSize=0 here rather
            // than the live database's real size — RestoreStorage.requiredBytes' formula
            // still reserves 2x candidateSize (a bit more than the single copy preview
            // actually writes), a deliberately conservative reuse of the same shared
            // free-space policy execute() uses, rather than a second bespoke formula.
            RestoreTransaction.ensureSpace(
                dir = copy.parentFile ?: error("Preview staging directory is unavailable"),
                liveSize = 0L,
                candidateSize = backup.sizeBytes,
            )
            onProgress(RestoreProgressStep.COPYING)
            val copyJob = currentCoroutineContext()[Job]
            RestoreTransaction.copySafOnce(
                context,
                backup.databaseUri.toString(),
                copy,
                isCancelled = { copyJob?.isActive == false },
            )
            onProgress(RestoreProgressStep.VALIDATING)
            // migrationSnapshot=true: a preview must work on a candidate at any restorable
            // schema version, not only one already at the current version.
            BackupSnapshotValidator.validate(copy, migrationSnapshot = true)
            SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                BackupPreviewCounts(
                    playlistCount = countRows(db, "playlists"),
                    songCount = countRows(db, "songs"),
                    playlistEntryCount = countRows(db, "playlist_songs"),
                )
            }
        } finally {
            stage.close()
        }
    }
}

private fun countRows(database: SQLiteDatabase, table: String): Int =
    database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
