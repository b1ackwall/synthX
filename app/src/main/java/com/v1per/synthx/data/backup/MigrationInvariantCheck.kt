package com.v1per.synthx.data.backup

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File

/**
 * Raised when a migration ran but failed the check in [MigrationInvariantCheck.verify],
 * or when the startup gate finds [PreferencesManager.migrationInvariantFailurePending] already set
 * from a previous attempt. Either way, the live database must not be treated as usable — recovery
 * is offered from the internal snapshot [MigrationSnapshotStore] captured just before the
 * migration ran.
 */
internal class MigrationInvariantFailedException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Runs immediately after a migration completes, before the startup gate ever reports readiness.
 * Reuses the same integrity/foreign-key/Room-identity/table checks [BackupSnapshotValidator]
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
 * Decides whether the startup gate should still show the recovery-required prompt, instead of
 * trusting [PreferencesManager.migrationInvariantFailurePending] and
 * [MigrationValidationMarker]'s presence at face value.
 *
 * The two signals are deliberately NOT treated the same way:
 *  - [MigrationValidationMarker] being present means validation never provably ran to completion
 *    — that is an unconditional "recovery required" signal on its own. There is nothing
 *    to self-heal here: if verify() never ran, no fact about the live database's current
 *    structural validity can stand in for the check that was actually supposed to happen.
 *  - [PreferencesManager.migrationInvariantFailurePending] being set means a completed validation
 *    genuinely failed, or the flag is a stale leftover from an older build's recovery ordering,
 *    which deleted the internal snapshot before durably clearing this flag. A death in that
 *    exact window leaves a permanently un-recoverable "recovery required" prompt even though the
 *    live database was already successfully restored. This flag alone is what self-heals:
 *    if the live database is genuinely valid right now AND there is no snapshot left to recover
 *    from anyway (recovery would be structurally impossible), the flag is cleared and the app is
 *    allowed to proceed normally. If the live database is NOT provably valid, or a snapshot DOES
 *    still exist, the flag falls through to the existing recovery-required flow unchanged.
 */
internal object MigrationRecoveryGate {
    fun isRecoveryStillRequired(context: Context): Boolean {
        // Unconditional — see the class doc for why this specific signal never self-heals.
        if (MigrationValidationMarker.isPresent(context)) return true

        val preferences = PreferencesManager(context)
        if (!preferences.migrationInvariantFailurePending) return false

        // A snapshot existing means a real, unresolved validation failure — the live database's
        // validity is not yet proven and recovery must still be offered.
        if (MigrationSnapshotStore.snapshotForCurrentVersion(context) != null) return true

        val liveValid = runCatching {
            BackupSnapshotValidator.validate(
                context.getDatabasePath(SynthXDatabase.DB_NAME),
                migrationSnapshot = false,
            )
        }.isSuccess
        if (!liveValid) return true

        // Structurally impossible to recover from (no snapshot) and nothing to recover FROM is
        // needed anyway (the live DB already validates as current) — this is exactly the
        // stale-flag lockout shape the class doc describes. Clear the stale flag and let the app proceed normally. If the durable
        // clear itself can't be confirmed, stay conservative and keep prompting rather than
        // silently claiming a self-heal that might not have landed.
        return !preferences.setMigrationInvariantFailurePendingDurably(false)
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

        // The failure flag is cleared DURABLY, and confirmed, before the snapshot it protects
        // is deleted. The live database has already been proven valid above, so if the process
        // dies right after this line, the flag is correctly cleared and the snapshot is still
        // sitting there for a retried recovery (or MigrationRecoveryGate's self-heal) to fall
        // back on. Deleting the snapshot first could die before the flag-clear landed,
        // permanently locking out recovery for a database that was actually already fine.
        check(PreferencesManager(context).setMigrationInvariantFailurePendingDurably(false)) {
            "Could not durably clear the migration invariant failure flag after recovery"
        }
        MigrationValidationMarker.clear(context)
        MigrationSnapshotStore.clearAfterRecovery(context)
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
