package com.v1per.synthx.data.backup

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Owns local backup stages and makes later same-process scavenging safe. */
internal object BackupStagingCoordinator {
    private const val TAG = "BackupStaging"
    private const val DIRECTORY = "backup_staging"
    private val lock = Any()
    private val activeIds = mutableSetOf<String>()
    private val recognized = Regex(
        "^(snapshot|verify|preview)-" +
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})" +
            "(?:\\.partial)?\\.db(?:-(?:journal|wal|shm))?$",
        RegexOption.IGNORE_CASE,
    )

    internal class Lease internal constructor(
        val file: File,
        private val id: String,
    ) : Closeable {
        override fun close() {
            deleteFileSet(file)
            synchronized(lock) { activeIds.remove(id) }
        }
    }

    fun acquire(context: Context, kind: String): Lease {
        require(kind == "snapshot" || kind == "verify" || kind == "preview")
        val id = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create backup staging directory" }
        val suffix = if (kind == "snapshot") ".partial.db" else ".db"
        synchronized(lock) { check(activeIds.add(id)) }
        return Lease(File(directory, "$kind-$id$suffix"), id)
    }

    /** Safe at process startup; active filtering also makes explicit test/later passes safe. */
    fun scavenge(context: Context) {
        val directory = File(context.noBackupFilesDir, DIRECTORY)
        val active = synchronized(lock) { activeIds.toSet() }
        directory.listFiles().orEmpty().forEach { file ->
            val match = recognized.matchEntire(file.name) ?: return@forEach
            if (match.groupValues[2] in active) return@forEach
            if (!file.delete() && file.exists()) Log.w(TAG, "Could not remove orphaned stage ${file.name}")
        }
    }

    private fun deleteFileSet(file: File) {
        listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm"))
            .filter(File::exists)
            .forEach { candidate ->
                if (!candidate.delete()) Log.w(TAG, "Could not remove local stage ${candidate.name}")
            }
    }
}

/** The exact synchronous hook invoked before SynthXApplication opens any startup gate. */
internal fun scavengeBackupStagesAtProcessStart(context: Context) {
    BackupStagingCoordinator.scavenge(context)
}
