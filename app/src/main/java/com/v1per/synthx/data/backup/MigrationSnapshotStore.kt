package com.v1per.synthx.data.backup

import android.content.Context
import android.util.Log
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File

/**
 * The unconditional internal safety net for a pending Room migration.
 *
 * Unlike the SAF-based [BackupManager] pipeline, this never depends on a user-configured backup
 * folder or a SAF grant still being valid — it writes to [Context.getNoBackupFilesDir], which is
 * always available to the app itself and is never part of [BackupTier]'s retention system.
 * Exactly one snapshot is kept at a time: the one guarding whichever migration most
 * recently ran. [recordSuccessfulLaunch] retires it once the new version has proven itself across
 * a few ordinary launches.
 */
internal object MigrationSnapshotStore {
    private const val TAG = "MigrationSnapshotStore"
    private const val SNAPSHOT_FILE_NAME = "premigration_snapshot.db"

    /** How many ordinary launches at the protected version must succeed before the snapshot is retired. */
    internal const val CONFIRMED_LAUNCHES_TO_RETIRE = 3

    fun snapshotFile(context: Context): File = File(context.noBackupFilesDir, SNAPSHOT_FILE_NAME)

    /**
     * Snapshots the live database *before* Room ever opens it at [toVersion]. Any snapshot left
     * over from an earlier migration is discarded first — only one is ever kept, and it always
     * protects the migration most recently attempted.
     *
     * Unconditional by design: no SAF folder, grant, or user configuration is consulted.
     */
    fun createSnapshot(context: Context, fromVersion: Int, toVersion: Int) {
        val sourceFile = context.getDatabasePath(SynthXDatabase.DB_NAME)
        check(sourceFile.isFile) { "No live database to snapshot before migration" }

        val destination = snapshotFile(context)
        deleteSnapshotFiles(destination)

        BackupSnapshotter.createForMigration(sourceFile, destination)
        BackupSnapshotter.forceToStorage(destination)
        val validated = BackupSnapshotValidator.validate(destination, migrationSnapshot = true)
        check(validated.schema.userVersion == fromVersion) {
            "Internal snapshot captured version ${validated.schema.userVersion}, expected $fromVersion"
        }

        val preferences = PreferencesManager(context)
        preferences.migrationSnapshotFromVersion = fromVersion
        preferences.migrationSnapshotForVersion = toVersion
        preferences.migrationSnapshotConfirmedLaunches = 0
    }

    /**
     * Called once per successful app startup at the current database version — see
     * [DatabaseStartupController]'s `onReady` hook. A launch only ever advances the counter for
     * the version the current snapshot actually protects; a stale snapshot from a superseded
     * migration attempt (or none at all) is left untouched here.
     */
    fun recordSuccessfulLaunch(context: Context) {
        // Defense in depth: reachable in practice only once a migration attempt
        // has actually cleared the marker (performProductionMigration rethrows on any failure
        // before onReady ever fires), but this guard makes that guarantee explicit and
        // independent of the surrounding control flow rather than relying solely on it.
        if (MigrationValidationMarker.isPresent(context)) return

        val preferences = PreferencesManager(context)
        val forVersion = preferences.migrationSnapshotForVersion
        if (forVersion == -1 || forVersion != SynthXDatabase.VERSION) return
        if (!snapshotFile(context).isFile) {
            clearMetadata(preferences)
            return
        }

        val launches = preferences.migrationSnapshotConfirmedLaunches + 1
        if (launches >= CONFIRMED_LAUNCHES_TO_RETIRE) {
            deleteSnapshotFiles(snapshotFile(context))
            clearMetadata(preferences)
        } else {
            preferences.migrationSnapshotConfirmedLaunches = launches
        }
    }

    /**
     * The snapshot to offer for recovery after a post-migration invariant failure, or `null` if
     * none exists or it no longer targets the live build's version.
     */
    fun snapshotForCurrentVersion(context: Context): File? {
        val preferences = PreferencesManager(context)
        if (preferences.migrationSnapshotForVersion != SynthXDatabase.VERSION) return null
        val file = snapshotFile(context)
        return file.takeIf(File::isFile)
    }

    /** Called after the internal snapshot has been restored back over the live database. */
    fun clearAfterRecovery(context: Context) {
        deleteSnapshotFiles(snapshotFile(context))
        clearMetadata(PreferencesManager(context))
    }

    private fun clearMetadata(preferences: PreferencesManager) {
        preferences.migrationSnapshotFromVersion = -1
        preferences.migrationSnapshotForVersion = -1
        preferences.migrationSnapshotConfirmedLaunches = 0
    }

    private fun deleteSnapshotFiles(file: File) {
        listOf(
            file,
            File("${file.path}-journal"),
            File("${file.path}-wal"),
            File("${file.path}-shm"),
        ).filter(File::exists).forEach { candidate ->
            if (!candidate.delete()) Log.w(TAG, "Could not remove ${candidate.name}")
        }
    }
}
