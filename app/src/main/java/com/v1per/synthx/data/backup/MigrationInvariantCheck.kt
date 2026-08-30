package com.v1per.synthx.data.backup

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File

/**
 * Raised when a migration ran but failed the check in [MigrationInvariantCheck.verify] (N6/P2.3),
 * or when the startup gate finds [PreferencesManager.migrationInvariantFailurePending] already set
 * from a previous attempt. Either way, the live database must not be treated as usable — recovery
 * is offered from the internal snapshot [MigrationSnapshotStore] captured just before the
 * migration ran.
 */
internal class MigrationInvariantFailedException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Runs immediately after a migration completes, before the startup gate ever reports readiness
 * (P2.3). Reuses the same integrity/foreign-key/Room-identity/table checks [BackupSnapshotValidator]
 * already runs against snapshot files, applied here directly to the live, just-migrated database.
 */
internal object MigrationInvariantCheck {
    fun verify(database: SynthXDatabase, expectedVersion: Int) {
        val schema = checkNotNull(BackupSchemaContracts.forVersion(expectedVersion)) {
            "No backup schema contract for current database version $expectedVersion"
        }
        BackupSnapshotValidator.validateLive(database.openHelper.writableDatabase, schema)
    }
}

/**
 * The lightweight, internal-only recovery path offered when [MigrationInvariantCheck] fails.
 *
 * [RestoreTransaction] was deliberately not reused here: it is built for restoring a SAF-sourced
 * backup into a *running* app (WorkManager scan draining, playback/queue quiescing, a multi-phase
 * on-disk state machine so a crash mid-restore can resume) — none of which applies at this point.
 * The gate hasn't granted readiness yet, so no ViewModel, repository, playback engine, or worker
 * has ever touched Room in this process; the only thing "running" is the failed startup attempt
 * itself. What's needed is a straight file swap back to the known-good pre-migration snapshot,
 * followed by a mandatory process restart (matching this app's existing convention for any
 * database-file replacement — see RestoreCoordinator's own RESTART_REQUIRED terminal state).
 */
internal object MigrationRecoveryAction {
    fun restoreFromInternalSnapshot(context: Context): Result<Unit> = runCatching {
        val snapshot = MigrationSnapshotStore.snapshotForCurrentVersion(context)
            ?: error("No internal pre-migration snapshot is available to restore")

        SynthXDatabase.closeInstance()

        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
        val dir = live.parentFile ?: error("Database directory is unavailable")
        check(dir.isDirectory) { "Database directory is unavailable" }

        if (live.exists()) {
            val failed = uniqueFailedName(dir)
            rawRename(live, failed)
            detachSidecars(live, failed)
        }

        snapshot.copyTo(live, overwrite = false)
        forceToStorage(live)
        val validated = BackupSnapshotValidator.validate(live, migrationSnapshot = true)
        check(validated.schema.userVersion == PreferencesManager(context).migrationSnapshotFromVersion) {
            "Restored database is not the version the internal snapshot claimed to protect"
        }

        MigrationSnapshotStore.clearAfterRecovery(context)
        PreferencesManager(context).migrationInvariantFailurePending = false
    }

    private fun uniqueFailedName(dir: File): File {
        var candidate = File(dir, "synthx.migration-failed.db")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "synthx.migration-failed-$index.db")
            index++
        }
        return candidate
    }

    private fun detachSidecars(source: File, target: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(source.path + suffix)
            if (sidecar.exists()) rawRename(sidecar, File(target.path + suffix))
        }
    }

    private fun rawRename(from: File, to: File) {
        check(from.exists()) { "Missing ${from.name}" }
        check(!to.exists()) { "Unexpected ${to.name}" }
        try {
            Os.rename(from.path, to.path)
        } catch (error: ErrnoException) {
            throw IllegalStateException("Could not rename ${from.name}", error)
        }
    }

    private fun forceToStorage(file: File) =
        java.io.RandomAccessFile(file, "rw").channel.use { it.force(true) }
}
