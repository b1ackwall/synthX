package com.v1per.synthx.data.backup

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Durable "validation pending" marker for a migration in flight (P3-T3 / R1-F08).
 *
 * [DatabaseStartupGate.performProductionMigration] writes this file *before* Room's forced open
 * commits the migration, and clears it *only* after [MigrationInvariantCheck.verify] has
 * succeeded. A process death anywhere in between — mid-migration, after Room's commit but before
 * `verify` ever runs, or after `verify` passes but before the clear lands — leaves this file
 * present. Its mere presence on a later launch is proof that validation did not provably complete
 * last time, independent of what the live database's own `user_version` reports (which may
 * already read as current) or what [PreferencesManager.migrationInvariantFailurePending] says.
 *
 * Deliberately a file with an fsync'd rename, not a [android.content.SharedPreferences] entry:
 * this marker must survive a death that happens *before* the very step it guards, so `apply()`'s
 * unspecified, asynchronous flush timing is not good enough here — the write must be durable by
 * the time this call returns. Modeled directly on [RestoreTransaction]'s
 * write-temp-then-rename-then-fsync-directory discipline, minus the multi-phase state machine
 * that subsystem needs and this one does not: there is exactly one bit of information worth
 * persisting here (a migration validation is outstanding), so a single marker file with no phase
 * enum is the right-sized adaptation of that pattern, not the full apparatus.
 */
internal object MigrationValidationMarker {
    private const val MARKER_FILE_NAME = "migration_validation_pending.json"
    private const val MARKER_TEMP_FILE_NAME = "migration_validation_pending.tmp"
    private val json = Json { ignoreUnknownKeys = false }

    @Serializable
    internal data class Marker(
        val fromVersion: Int,
        val toVersion: Int,
        val createdAt: Long,
    )

    private fun dir(context: Context): File = context.noBackupFilesDir
    private fun markerFile(context: Context): File = File(dir(context), MARKER_FILE_NAME)

    /** Writes the marker durably. Must complete before Room's forced open runs the migration. */
    fun write(context: Context, fromVersion: Int, toVersion: Int) {
        val marker = Marker(fromVersion, toVersion, System.currentTimeMillis())
        val directory = dir(context)
        val temp = File(directory, MARKER_TEMP_FILE_NAME)
        FileOutputStream(temp).use { output ->
            output.write(json.encodeToString(Marker.serializer(), marker).encodeToByteArray())
            output.fd.sync()
        }
        try {
            Os.rename(temp.path, markerFile(context).path)
        } catch (error: ErrnoException) {
            throw IllegalStateException("Could not durably record the migration validation marker", error)
        }
        syncDirectory(directory)
    }

    /** True the moment the marker file exists, regardless of whether its contents decode. A
     *  malformed marker is still evidence that validation was left outstanding — its presence
     *  alone is the signal, not its parsed contents. */
    fun isPresent(context: Context): Boolean = markerFile(context).isFile

    /** Cleared only once [MigrationInvariantCheck.verify] has actually succeeded. */
    fun clear(context: Context) {
        val directory = dir(context)
        markerFile(context).delete()
        File(directory, MARKER_TEMP_FILE_NAME).delete()
        syncDirectory(directory)
    }

    private fun syncDirectory(dir: File) {
        runCatching {
            Os.open(dir.path, OsConstants.O_RDONLY, 0).also { descriptor ->
                try {
                    Os.fsync(descriptor)
                } finally {
                    Os.close(descriptor)
                }
            }
        }
    }
}
