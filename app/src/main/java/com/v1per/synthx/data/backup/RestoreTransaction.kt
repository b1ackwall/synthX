package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.playback.QueuePersistence
import com.v1per.synthx.data.scanner.LibraryScanWorker
import androidx.work.WorkManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.first

/** The durable, on-disk boundary of a restore. All files are siblings of the Room database. */
internal object RestoreTransaction {
    private const val STATE_FILE = "synthx.restore-state.json"
    private const val STATE_TEMP_FILE = "synthx.restore-state.tmp"
    private const val RESERVE_BYTES = 64L * 1024L * 1024L
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    @Serializable
    enum class Phase { PREPARED, LIVE_MOVE_INTENT, LIVE_MOVED, INSTALL_INTENT, CANDIDATE_INSTALLED, POST_VALIDATED, ROLLBACK_INTENT, ROLLED_BACK, COMPLETE }

    @Serializable
    data class State(
        val operationId: String,
        val sourceName: String,
        val sourceUri: String,
        val candidateName: String,
        val oldName: String,
        val safetyName: String,
        val failedName: String,
        val candidateSha256: String,
        val candidateSize: Long,
        val candidateVersion: Int,
        val safetySha256: String,
        val safetySize: Long,
        val phase: Phase,
        val createdAt: Long,
        val updatedAt: Long = createdAt,
    )

    class PostCloseFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    suspend fun execute(context: Context, backup: BackupInfo, metadata: BackupMetadata): Result<Unit> = runCatching {
        metadata.requireCompleteForPublication()
        require(metadata.file == backup.fileName) { "Backup metadata does not match the selected database" }
        val actualVersion = metadata.dbVersion
        require(actualVersion <= SynthXDatabase.VERSION) {
            "This backup was created by a newer version of synthX and cannot be restored with this version."
        }
        val schema = BackupSchemaContracts.forVersion(actualVersion)
            ?: error("This backup uses an unsupported synthX schema")
        require(schema.hasMigrationPathToCurrent) { "No supported migration path exists for this backup" }

        val dir = context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile
            ?: error("Database directory is unavailable")
        check(dir.isDirectory || dir.mkdirs()) { "Database directory is unavailable" }
        recoverIfNeeded(context, dir)

        val op = UUID.randomUUID().toString().replace("-", "")
        val candidate = File(dir, "synthx.restore-$op.candidate.db")
        val old = File(dir, "synthx.restore-$op.old.db")
        val safety = File(dir, "synthx.restore-$op.safety.db")
        val failed = File(dir, "synthx.restore-$op.failed.db")
        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)

        try {
            val sourceDigest = copySafOnce(context, backup.databaseUri.toString(), candidate)
            require(sourceDigest.sizeBytes == metadata.sizeBytes && sourceDigest.sha256 == metadata.sha256) {
                "Backup bytes do not match its verified size and SHA-256 metadata"
            }
            val sourceValidated = BackupSnapshotValidator.validate(candidate, migrationSnapshot = true)
            require(sourceValidated.schema.userVersion == actualVersion) {
                "Backup metadata version does not match the database"
            }
            if (sourceValidated.schema.userVersion < SynthXDatabase.VERSION) migrateCandidate(context, candidate.name)
            val finalValidated = BackupSnapshotValidator.validate(candidate, migrationSnapshot = false)
            val candidateDigest = BackupHashing.hash(candidate)
            force(candidate)
            ensureSpace(dir, live.length(), candidateDigest.sizeBytes)

            // This is deliberately internal and mandatory. External SAF safety publication is not
            // part of the restore correctness boundary.
            RestoreCoordinator.exclusive {
                awaitScanIdle(context)
                ensureSpace(dir, live.length(), candidateDigest.sizeBytes)
                val room = SynthXDatabase.getInstance(context)
                BackupSnapshotter.createFromRoom(room.openHelper.writableDatabase, safety)
                BackupSnapshotter.forceToStorage(safety)
                BackupSnapshotValidator.validate(safety, migrationSnapshot = true)
                val safetyDigest = BackupHashing.hash(safety)
                val initial = State(op, backup.fileName, backup.databaseUri.toString(), candidate.name,
                    old.name, safety.name, failed.name, candidateDigest.sha256, candidateDigest.sizeBytes,
                    finalValidated.schema.userVersion, safetyDigest.sha256, safetyDigest.sizeBytes,
                    Phase.PREPARED, System.currentTimeMillis())
                writeState(dir, initial)

                try {
                    // Queue writes have an independent asynchronous owner; it must be drained
                    // before the Room close boundary, not merely left to process timing.
                    QueuePersistence.quiesceAllForRestore()
                    checkpointAndClose(context, live)
                    writeState(dir, initial.copy(phase = Phase.LIVE_MOVE_INTENT, updatedAt = System.currentTimeMillis()))
                    rename(live, old)
                    syncDirectory(dir)
                    writeState(dir, initial.copy(phase = Phase.LIVE_MOVED, updatedAt = System.currentTimeMillis()))
                    writeState(dir, initial.copy(phase = Phase.INSTALL_INTENT, updatedAt = System.currentTimeMillis()))
                    rename(candidate, live)
                    syncDirectory(dir)
                    writeState(dir, initial.copy(phase = Phase.CANDIDATE_INSTALLED, updatedAt = System.currentTimeMillis()))
                    validateInstalled(live, initial)
                    writeState(dir, initial.copy(phase = Phase.POST_VALIDATED, updatedAt = System.currentTimeMillis()))
                    RestoreCoordinator.markRestartRequired(rolledBack = false)
                } catch (e: Exception) {
                    rollback(dir, live, initial, e)
                    throw PostCloseFailure("Restore was rolled back; close and reopen synthX", e)
                }
            }
        } catch (e: PostCloseFailure) {
            throw e
        } catch (e: Exception) {
            candidate.delete()
            // Quiesce/checkpoint failures before Room is closed are ordinary pre-close failures:
            // reopen admission so the existing live session remains usable.
            if (RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.RESTORE_PREPARING) {
                RestoreCoordinator.markNormal()
            }
            throw e
        }
    }

    /** Runs before any normal database entrypoint. It never trusts a filename or phase alone. */
    fun recoverIfNeeded(context: Context, dir: File): RestoreRecovery = runCatching {
        val state = readState(dir) ?: return RestoreRecovery.NORMAL
        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
        val old = File(dir, state.oldName)
        val safety = File(dir, state.safetyName)
        val candidate = File(dir, state.candidateName)
        when (state.phase) {
            Phase.POST_VALIDATED, Phase.COMPLETE -> {
                validateInstalled(live, state)
                writeState(dir, state.copy(phase = Phase.COMPLETE, updatedAt = System.currentTimeMillis()))
                RestoreRecovery.NORMAL
            }
            Phase.PREPARED -> {
                if (live.isFile) { cleanupPreClose(dir, state); RestoreRecovery.NORMAL } else recoverOldOrSafety(dir, live, old, safety, state)
            }
            Phase.CANDIDATE_INSTALLED -> {
                if (runCatching { validateInstalled(live, state) }.isSuccess) {
                    writeState(dir, state.copy(phase = Phase.POST_VALIDATED, updatedAt = System.currentTimeMillis()))
                    RestoreRecovery.RESTART_REQUIRED
                } else recoverOldOrSafety(dir, live, old, safety, state)
            }
            Phase.ROLLED_BACK -> if (validateStandalone(live, currentOnly = false)) RestoreRecovery.ROLLED_BACK_RESTART_REQUIRED else RestoreRecovery.EMERGENCY
            else -> recoverOldOrSafety(dir, live, old, safety, state)
        }
    }.getOrElse { RestoreRecovery.EMERGENCY }

    private fun recoverOldOrSafety(dir: File, live: File, old: File, safety: File, state: State): RestoreRecovery {
        if (old.isFile && validateStandalone(old, currentOnly = false)) {
            if (live.exists()) rename(live, File(dir, state.failedName))
            rename(old, live); syncDirectory(dir)
            if (validateStandalone(live, currentOnly = false)) {
                writeState(dir, state.copy(phase = Phase.ROLLED_BACK, updatedAt = System.currentTimeMillis()))
                return RestoreRecovery.ROLLED_BACK_RESTART_REQUIRED
            }
        }
        // Safety is a standalone VACUUM snapshot. Copying it would violate the restore rule;
        // promote it through a same-directory candidate name instead.
        if (safety.isFile && validateSafety(safety, state) && !live.exists()) {
            rename(safety, live); syncDirectory(dir)
            if (validateStandalone(live, currentOnly = false)) {
                writeState(dir, state.copy(phase = Phase.ROLLED_BACK, updatedAt = System.currentTimeMillis()))
                return RestoreRecovery.ROLLED_BACK_RESTART_REQUIRED
            }
        }
        return RestoreRecovery.EMERGENCY
    }

    private fun rollback(dir: File, live: File, state: State, cause: Exception) {
        writeState(dir, state.copy(phase = Phase.ROLLBACK_INTENT, updatedAt = System.currentTimeMillis()))
        val old = File(dir, state.oldName)
        if (live.exists()) rename(live, File(dir, state.failedName))
        if (old.isFile && validateStandalone(old, currentOnly = false)) {
            rename(old, live); syncDirectory(dir)
            check(validateStandalone(live, currentOnly = false)) { "Rollback database validation failed" }
            writeState(dir, state.copy(phase = Phase.ROLLED_BACK, updatedAt = System.currentTimeMillis()))
            RestoreCoordinator.markRestartRequired(rolledBack = true)
        } else {
            val safety = File(dir, state.safetyName)
            if (!live.exists() && safety.isFile && validateSafety(safety, state)) {
                rename(safety, live); syncDirectory(dir)
                check(validateStandalone(live, currentOnly = false)) { "Safety rollback database validation failed" }
                writeState(dir, state.copy(phase = Phase.ROLLED_BACK, updatedAt = System.currentTimeMillis()))
                RestoreCoordinator.markRestartRequired(rolledBack = true)
            } else throw IllegalStateException("Automatic rollback could not validate old or safety database", cause)
        }
    }

    private fun checkpointAndClose(context: Context, live: File) {
        val room = SynthXDatabase.getInstance(context)
        room.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "WAL checkpoint remained busy" }
        }
        SynthXDatabase.closeInstance()
        RestoreCoordinator.markClosed()
        // A single framework open/close finishes recovery of a lingering rollback journal/WAL.
        SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "WAL recovery remained busy" }
            }
        }
        listOf("-wal", "-shm", "-journal").map { File(live.path + it) }.forEach { sidecar ->
            check(!sidecar.exists() || sidecar.delete()) { "Could not clear recovered ${sidecar.name}" }
        }
    }

    private fun migrateCandidate(context: Context, candidateName: String) {
        val db = SynthXDatabase.createStandalone(context, candidateName)
        try { db.openHelper.writableDatabase } finally { db.close() }
    }

    private suspend fun awaitScanIdle(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME)
        workManager.getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
            .first { infos -> infos.all { it.state.isFinished } }
    }

    private fun copySafOnce(context: Context, uri: String, destination: File): ByteDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (n == 0) continue
                    output.write(buffer, 0, n); digest.update(buffer, 0, n); count += n
                }
                output.fd.sync()
            }
        } ?: error("Could not read the selected backup")
        return ByteDigest(count, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun ensureSpace(dir: File, liveSize: Long, candidateSize: Long) {
        val required = liveSize + liveSize + candidateSize * 2L + RESERVE_BYTES
        check(StatFs(dir.path).availableBytes >= required) { "Not enough internal storage to restore safely" }
    }

    private fun validateInstalled(live: File, state: State) {
        check(live.isFile && live.length() == state.candidateSize) { "Installed database size does not match candidate" }
        check(BackupHashing.hash(live).sha256 == state.candidateSha256) { "Installed database hash does not match candidate" }
        val result = BackupSnapshotValidator.validate(live, migrationSnapshot = false)
        check(result.schema.userVersion == SynthXDatabase.VERSION) { "Installed database is not current" }
    }
    private fun validateStandalone(file: File, currentOnly: Boolean): Boolean = runCatching {
        BackupSnapshotValidator.validate(file, migrationSnapshot = !currentOnly); true
    }.getOrDefault(false)
    private fun validateSafety(file: File, state: State): Boolean =
        file.isFile && file.length() == state.safetySize &&
            BackupHashing.hash(file).sha256 == state.safetySha256 &&
            validateStandalone(file, currentOnly = false)
    private fun force(file: File) = RandomAccessFile(file, "rw").channel.use { it.force(true) }
    private fun rename(from: File, to: File) {
        check(from.isFile) { "Missing ${from.name}" }; check(!to.exists()) { "Unexpected ${to.name}" }
        try { Os.rename(from.path, to.path) } catch (e: ErrnoException) { throw IllegalStateException("Could not rename ${from.name}", e) }
    }
    private fun writeState(dir: File, state: State) {
        val temp = File(dir, STATE_TEMP_FILE); val target = File(dir, STATE_FILE)
        FileOutputStream(temp).use { out -> out.write(json.encodeToString(State.serializer(), state).encodeToByteArray()); out.fd.sync() }
        try { Os.rename(temp.path, target.path) } catch (e: ErrnoException) {
            throw IllegalStateException("Could not atomically promote restore state", e)
        }
        syncDirectory(dir)
    }
    private fun readState(dir: File): State? = File(dir, STATE_FILE).takeIf(File::isFile)?.let { json.decodeFromString(State.serializer(), it.readText()) }
    private fun cleanupPreClose(dir: File, state: State) { listOf(state.candidateName, state.safetyName).forEach { File(dir, it).delete() }; File(dir, STATE_FILE).delete() }
    private fun syncDirectory(dir: File) { runCatching { Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0).also { fd -> try { Os.fsync(fd) } finally { Os.close(fd) } } } }
}

internal enum class RestoreRecovery { NORMAL, RESTART_REQUIRED, ROLLED_BACK_RESTART_REQUIRED, EMERGENCY }
