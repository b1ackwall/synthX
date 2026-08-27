package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.work.WorkManager
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.playback.ExoPlayerEngine
import com.v1per.synthx.playback.QueuePersistence
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The durable, on-disk boundary of a restore. All database artifacts are same-directory. */
internal object RestoreTransaction {
    private const val STATE_FILE = "synthx.restore-state.json"
    private const val STATE_TEMP_FILE = "synthx.restore-state.tmp"
    private const val INCIDENT_FILE = "synthx.restore-incident.id"
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    @Volatile private var lastDiagnosticOperationId: String? = null

    @Serializable
    enum class Phase {
        PREPARED,
        LIVE_MOVE_INTENT,
        LIVE_MOVED,
        INSTALL_INTENT,
        CANDIDATE_INSTALLED,
        POST_VALIDATED,
        ROLLBACK_INTENT,
        ROLLED_BACK,
        COMPLETE,
    }

    @Serializable
    enum class Outcome { INSTALLED, ROLLED_BACK }

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
        val oldSha256: String? = null,
        val oldSize: Long? = null,
        val oldVersion: Int? = null,
        val outcome: Outcome = Outcome.INSTALLED,
    )

    class PostCloseFailure(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    suspend fun execute(
        context: Context,
        backup: BackupInfo,
        metadata: BackupMetadata,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
        sourceOpener: RestoreSourceOpener = RestoreSourceOpener.CONTENT_RESOLVER,
    ): Result<Unit> = RestoreExecutionCoordinator.serialize { runCatching {
        when (RestoreEligibility.assess(metadata, backup.fileName)) {
            RestoreEligibility.TRUSTED_CURRENT,
            RestoreEligibility.TRUSTED_OLDER_SUPPORTED -> Unit
            RestoreEligibility.NEWER_SCHEMA -> error(
                "This backup was created by a newer version of synthX and cannot be restored with this version."
            )
            RestoreEligibility.UNSUPPORTED_SCHEMA -> error(
                "No supported migration path exists for this backup"
            )
            RestoreEligibility.LEGACY_OR_MALFORMED -> error(
                "Legacy or malformed backup metadata cannot be restored"
            )
        }

        val dir = context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile
            ?: error("Database directory is unavailable")
        check(dir.isDirectory || dir.mkdirs()) { "Database directory is unavailable" }
        check(recoverIfNeeded(context, dir, faults) == RestoreRecovery.NORMAL) {
            "An existing restore transaction requires recovery before another restore can start"
        }
        RestoreCoordinator.requireNormal()

        val op = UUID.randomUUID().toString().replace("-", "")
        val names = RestoreArtifactNames(op)
        val candidate = File(dir, names.candidate)
        val old = File(dir, names.old)
        val safety = File(dir, names.safety)
        val failed = File(dir, names.failed)
        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)

        try {
            val sourceDigest = copySafOnce(
                context,
                backup.databaseUri.toString(),
                candidate,
                faults,
                sourceOpener,
            )
            faults.hit(RestoreFaultPoint.SOURCE_SHA_VERIFY)
            require(
                sourceDigest.sizeBytes == metadata.sizeBytes &&
                    sourceDigest.sha256 == metadata.sha256
            ) { "Backup bytes do not match its verified size and SHA-256 metadata" }

            val sourceValidated = BackupSnapshotValidator.validate(candidate, migrationSnapshot = true)
            require(sourceValidated.schema.userVersion == metadata.dbVersion) {
                "Backup metadata version does not match the database"
            }
            if (sourceValidated.schema.userVersion < SynthXDatabase.VERSION) {
                faults.hit(RestoreFaultPoint.MIGRATION)
                migrateCandidate(context, candidate.name)
            }
            proveCandidateStandalone(candidate)
            val finalValidated = BackupSnapshotValidator.validate(candidate, migrationSnapshot = false)
            val candidateDigest = BackupHashing.hash(candidate)
            force(candidate)
            ensureSpace(dir, live.length(), candidateDigest.sizeBytes, faults)

            RestoreCoordinator.exclusive {
                awaitScanIdle(context)
                ensureSpace(dir, live.length(), candidateDigest.sizeBytes, faults)

                // Admission is already closed. Let every operation accepted before that
                // boundary finish so the mandatory safety snapshot includes all of them.
                RestoreCoordinator.awaitDrained()

                faults.hit(RestoreFaultPoint.SAFETY_SNAPSHOT)
                val room = SynthXDatabase.getInstanceForRestore(context)
                BackupSnapshotter.createFromRoom(room.openHelper.writableDatabase, safety)
                BackupSnapshotter.forceToStorage(safety)
                BackupSnapshotValidator.validate(safety, migrationSnapshot = false)
                val safetyDigest = BackupHashing.hash(safety)
                var state = State(
                    operationId = op,
                    sourceName = backup.fileName,
                    sourceUri = backup.databaseUri.toString(),
                    candidateName = candidate.name,
                    oldName = old.name,
                    safetyName = safety.name,
                    failedName = failed.name,
                    candidateSha256 = candidateDigest.sha256,
                    candidateSize = candidateDigest.sizeBytes,
                    candidateVersion = finalValidated.schema.userVersion,
                    safetySha256 = safetyDigest.sha256,
                    safetySize = safetyDigest.sizeBytes,
                    phase = Phase.PREPARED,
                    createdAt = System.currentTimeMillis(),
                )
                writeState(dir, state, faults)

                var permanentQuiesceStarted = false
                try {
                    permanentQuiesceStarted = true
                    ExoPlayerEngine.releaseAllForRestore()
                    QueuePersistence.quiesceAllForRestore()
                    SongRepositoryImpl.quiesceForRestore()
                    RestoreCoordinator.awaitDrained()
                    check(RestoreCoordinator.activeOperationCountForTest() == 0) {
                        "Tracked database operations did not drain before Room close"
                    }

                    val oldProof = checkpointCloseAndProveMain(context, live, old, faults)
                    state = state.copy(
                        oldSha256 = oldProof.sha256,
                        oldSize = oldProof.sizeBytes,
                        oldVersion = SynthXDatabase.VERSION,
                        updatedAt = System.currentTimeMillis(),
                    )
                    writeState(dir, state, faults)

                    state = state.copy(phase = Phase.LIVE_MOVE_INTENT, updatedAt = System.currentTimeMillis())
                    writeState(dir, state, faults)
                    rename(live, old, RestoreFaultPoint.LIVE_TO_OLD_RENAME, faults)
                    syncDirectory(dir)
                    state = state.copy(phase = Phase.LIVE_MOVED, updatedAt = System.currentTimeMillis())
                    writeState(dir, state, faults)

                    state = state.copy(phase = Phase.INSTALL_INTENT, updatedAt = System.currentTimeMillis())
                    writeState(dir, state, faults)
                    rename(candidate, live, RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME, faults)
                    syncDirectory(dir)
                    state = state.copy(phase = Phase.CANDIDATE_INSTALLED, updatedAt = System.currentTimeMillis())
                    writeState(dir, state, faults)

                    faults.hit(RestoreFaultPoint.INSTALLED_VALIDATION)
                    validateInstalled(live, state)
                    state = state.copy(phase = Phase.POST_VALIDATED, updatedAt = System.currentTimeMillis())
                    writeState(dir, state, faults)
                    RestoreCoordinator.markRestartRequired(rolledBack = false)
                } catch (failure: Exception) {
                    if (RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.RESTORE_PREPARING) {
                        if (!permanentQuiesceStarted) throw failure
                        // Room/live were not renamed, but queue/playback/repository owners
                        // were permanently stopped. Keep the file usable for cold startup
                        // and forbid this process from pretending normal operation resumed.
                        RestoreCoordinator.markRestartRequired(rolledBack = true)
                        throw PostCloseFailure(
                            "Restore stopped before live replacement; close and reopen synthX",
                            failure,
                        )
                    }
                    try {
                        rollbackCurrentProcess(dir, live, state, failure, faults)
                    } catch (rollbackFailure: Exception) {
                        lastDiagnosticOperationId = op
                        RestoreCoordinator.markEmergency(op)
                        throw PostCloseFailure(
                            "Automatic rollback could not validate old or safety database",
                            rollbackFailure,
                        )
                    }
                    throw PostCloseFailure("Restore was rolled back; close and reopen synthX", failure)
                }
            }
        } catch (failure: PostCloseFailure) {
            throw failure
        } catch (failure: Exception) {
            when (RestoreCoordinator.currentMode()) {
                RestoreCoordinator.Mode.NORMAL -> deleteFileSet(candidate)
                RestoreCoordinator.Mode.RESTORE_PREPARING -> {
                    deleteFileSet(candidate)
                    deleteFileSet(safety)
                    File(dir, STATE_FILE).delete()
                    File(dir, STATE_TEMP_FILE).delete()
                    syncDirectory(dir)
                    RestoreCoordinator.markNormal()
                }
                else -> Unit
            }
            throw failure
        }
    } }

    /** Runs before any normal database entrypoint. State is evidence, never sole authority. */
    fun recoverIfNeeded(
        context: Context,
        dir: File,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    ): RestoreRecovery {
        lastDiagnosticOperationId = null
        return try {
            val stateFile = File(dir, STATE_FILE)
            val state = stateFile.takeIf(File::isFile)?.let { file ->
                runCatching { decodeAndValidateState(file.readText()) }.getOrNull()
            }
            if (state == null) {
                recoverWithoutUsableState(context, dir, stateFile.isFile, faults)
            } else {
                recoverWithState(context, dir, state, faults)
            }
        } catch (_: Exception) {
            val discoveredOperation = dir.listFiles().orEmpty()
                .mapNotNull { RestoreArtifactNames.parseOperationId(it.name) }
                .toSet()
                .singleOrNull()
            lastDiagnosticOperationId = stableIncidentId(
                dir,
                lastDiagnosticOperationId ?: discoveredOperation,
            )
            RestoreRecovery.EMERGENCY
        }
    }

    private fun recoverWithState(
        context: Context,
        dir: File,
        state: State,
        faults: RestoreFaultInjector,
    ): RestoreRecovery {
        lastDiagnosticOperationId = state.operationId
        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
        val old = File(dir, state.oldName)
        val safety = File(dir, state.safetyName)
        val candidate = File(dir, state.candidateName)
        val facts = RestoreArtifactFacts(
            liveExists = live.isFile,
            liveValid = validateStandalone(live, currentOnly = true),
            liveIsCandidate = runCatching { validateInstalled(live, state) }.isSuccess,
            oldExists = old.isFile,
            oldValid = validateOld(old, state),
            safetyValid = validateSafety(safety, state),
            candidateExists = candidate.isFile,
        )

        return when (RestoreRecoveryPlanner.decide(state.phase, state.outcome, facts)) {
            RestoreRecoveryAction.CLEAN_PRE_CLOSE_AND_NORMAL -> {
                cleanupPreClose(dir, state)
                clearIncident(dir)
                RestoreRecovery.NORMAL
            }
            RestoreRecoveryAction.ACCEPT_INSTALLED_AND_RESTART -> {
                validateInstalled(live, state)
                writeState(
                    dir,
                    state.copy(
                        phase = Phase.POST_VALIDATED,
                        outcome = Outcome.INSTALLED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                    faults,
                )
                RestoreRecovery.RESTART_REQUIRED
            }
            RestoreRecoveryAction.COMPLETE_INSTALLED_AND_NORMAL -> {
                validateInstalled(live, state)
                val complete = state.copy(
                    phase = Phase.COMPLETE,
                    outcome = Outcome.INSTALLED,
                    updatedAt = System.currentTimeMillis(),
                )
                writeState(dir, complete, faults)
                cleanupAfterColdValidation(dir, complete)
                clearIncident(dir)
                RestoreRecovery.NORMAL
            }
            RestoreRecoveryAction.PROMOTE_OLD ->
                completeStartupRollback(dir, live, state, useSafety = false, faults)
            RestoreRecoveryAction.PROMOTE_SAFETY ->
                completeStartupRollback(dir, live, state, useSafety = true, faults)
            RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL -> {
                check(validateStandalone(live, currentOnly = true)) {
                    "Recovered live database did not validate"
                }
                val complete = state.copy(
                    phase = Phase.COMPLETE,
                    outcome = Outcome.ROLLED_BACK,
                    updatedAt = System.currentTimeMillis(),
                )
                writeState(dir, complete, faults)
                cleanupAfterColdValidation(dir, complete)
                clearIncident(dir)
                RestoreRecovery.NORMAL
            }
            RestoreRecoveryAction.EMERGENCY -> RestoreRecovery.EMERGENCY
        }.also { result ->
            if (result == RestoreRecovery.EMERGENCY) lastDiagnosticOperationId = state.operationId
        }
    }

    /** Missing/malformed state may touch only filenames owned by synthX restore operations. */
    private fun recoverWithoutUsableState(
        context: Context,
        dir: File,
        malformedStateExists: Boolean,
        faults: RestoreFaultInjector,
    ): RestoreRecovery {
        val artifacts = dir.listFiles().orEmpty().filter {
            RestoreArtifactNames.parseOperationId(it.name) != null
        }
        if (artifacts.isEmpty() && !malformedStateExists) {
            clearIncident(dir)
            return RestoreRecovery.NORMAL
        }
        val operationIds = artifacts.mapNotNull { RestoreArtifactNames.parseOperationId(it.name) }.toSet()
        if (operationIds.size != 1) {
            lastDiagnosticOperationId = stableIncidentId(dir, null)
            return RestoreRecovery.EMERGENCY
        }

        val op = operationIds.single()
        lastDiagnosticOperationId = op
        val names = RestoreArtifactNames(op)
        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
        val old = File(dir, names.old)
        val safety = File(dir, names.safety)
        val source = when {
            validateStandalone(old, currentOnly = true) -> old
            validateStandalone(safety, currentOnly = true) -> safety
            else -> return RestoreRecovery.EMERGENCY
        }

        parkLiveIfPresent(dir, live, names.failed)
        val point = if (source == old) {
            RestoreFaultPoint.OLD_ROLLBACK_RENAME
        } else {
            RestoreFaultPoint.SAFETY_FALLBACK_PROMOTION
        }
        rename(source, live, point, faults)
        syncDirectory(dir)
        check(validateStandalone(live, currentOnly = true)) {
            "Recovered database did not validate"
        }
        if (malformedStateExists) archiveCorruptState(dir, op)
        archiveRecoveredArtifacts(
            dir.listFiles().orEmpty().filter {
                RestoreArtifactNames.parseOperationId(it.name) == op
            },
        )
        clearIncident(dir)
        return RestoreRecovery.NORMAL
    }

    private fun completeStartupRollback(
        dir: File,
        live: File,
        original: State,
        useSafety: Boolean,
        faults: RestoreFaultInjector,
    ): RestoreRecovery {
        var state = original.copy(
            phase = Phase.ROLLBACK_INTENT,
            outcome = Outcome.ROLLED_BACK,
            updatedAt = System.currentTimeMillis(),
        )
        writeState(dir, state, faults)
        parkLiveIfPresent(dir, live, state.failedName)
        val source = File(dir, if (useSafety) state.safetyName else state.oldName)
        rename(
            source,
            live,
            if (useSafety) RestoreFaultPoint.SAFETY_FALLBACK_PROMOTION
            else RestoreFaultPoint.OLD_ROLLBACK_RENAME,
            faults,
        )
        syncDirectory(dir)
        check(validateStandalone(live, currentOnly = true)) {
            "Startup rollback database validation failed"
        }
        state = state.copy(phase = Phase.ROLLED_BACK, updatedAt = System.currentTimeMillis())
        writeState(dir, state, faults)
        state = state.copy(phase = Phase.COMPLETE, updatedAt = System.currentTimeMillis())
        writeState(dir, state, faults)
        cleanupAfterColdValidation(dir, state)
        clearIncident(dir)
        return RestoreRecovery.NORMAL
    }

    private fun rollbackCurrentProcess(
        dir: File,
        live: File,
        original: State,
        cause: Exception,
        faults: RestoreFaultInjector,
    ) {
        var state = original.copy(
            phase = Phase.ROLLBACK_INTENT,
            outcome = Outcome.ROLLED_BACK,
            updatedAt = System.currentTimeMillis(),
        )
        writeState(dir, state, faults)
        val old = File(dir, state.oldName)
        val safety = File(dir, state.safetyName)
        // SQLite's corruption handler may rename an invalid database to *.db.corrupt
        // while validateOld is running. Preserve the pre-validation fact so that rename
        // cannot be mistaken for "live->old never happened".
        val oldWasPresent = old.isFile
        val oldIsValid = validateOld(old, state)
        when {
            oldIsValid -> {
                parkLiveIfPresent(dir, live, state.failedName)
                rename(old, live, RestoreFaultPoint.OLD_ROLLBACK_RENAME, faults)
            }
            !oldWasPresent && validateStandalone(live, currentOnly = true) -> Unit
            validateSafety(safety, state) -> {
                parkLiveIfPresent(dir, live, state.failedName)
                rename(safety, live, RestoreFaultPoint.SAFETY_FALLBACK_PROMOTION, faults)
            }
            else -> throw IllegalStateException(
                "Automatic rollback could not validate old or safety database",
                cause,
            )
        }
        syncDirectory(dir)
        check(validateStandalone(live, currentOnly = true)) {
            "Rollback database validation failed"
        }
        state = state.copy(phase = Phase.ROLLED_BACK, updatedAt = System.currentTimeMillis())
        writeState(dir, state, faults)
        RestoreCoordinator.markRestartRequired(rolledBack = true)
    }

    private fun checkpointCloseAndProveMain(
        context: Context,
        live: File,
        old: File,
        faults: RestoreFaultInjector,
    ): ByteDigest {
        faults.hit(RestoreFaultPoint.WAL_CHECKPOINT)
        val room = SynthXDatabase.getInstanceForRestore(context)
        val roomResult = room.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use(::readCheckpointResult)
        check(roomResult.provesStandaloneMain()) {
            "Room WAL checkpoint did not prove a standalone main database: $roomResult"
        }

        SynthXDatabase.closeInstance()
        RestoreCoordinator.markClosed()

        val frameworkResult = SQLiteDatabase.openDatabase(
            live.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use(::readCheckpointResult)
        }
        check(frameworkResult.provesStandaloneMain()) {
            "Post-close WAL checkpoint did not prove a standalone main database: $frameworkResult"
        }

        // Residual sidecars are preserved under the old artifact basename, so none can
        // attach to the promoted candidate's live basename.
        quarantineSidecars(live, old)
        BackupSnapshotValidator.validate(live, migrationSnapshot = false)
        return BackupHashing.hash(live).also { digest ->
            check(digest.sizeBytes > 0 && digest.sizeBytes == live.length()) {
                "Live database changed while proving standalone state"
            }
        }
    }

    private fun proveCandidateStandalone(candidate: File) {
        val result = SQLiteDatabase.openDatabase(
            candidate.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use(::readCheckpointResult)
        }
        check(result.provesStandaloneMain()) {
            "Candidate WAL checkpoint did not prove a standalone main database: $result"
        }
        detachSidecars(candidate, File(candidate.path + ".checkpointed"))
    }

    internal fun checkpointFileForTest(file: File): WalCheckpointResult =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use(::readCheckpointResult)
        }

    internal fun proveCandidateStandaloneForTest(candidate: File) =
        proveCandidateStandalone(candidate)

    internal fun writeStateForTest(dir: File, state: State) =
        writeState(dir, state)

    private fun readCheckpointResult(cursor: android.database.Cursor): WalCheckpointResult {
        check(cursor.columnCount >= 3 && cursor.moveToFirst()) {
            "SQLite did not return a complete WAL checkpoint result"
        }
        return WalCheckpointResult(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
    }

    private fun migrateCandidate(context: Context, candidateName: String) {
        val database = SynthXDatabase.createStandalone(context, candidateName)
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    private suspend fun awaitScanIdle(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME)
        workManager.getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
            .first { infos -> infos.all { it.state.isFinished } }
    }

    internal fun copySafOnce(
        context: Context,
        uri: String,
        destination: File,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
        sourceOpener: RestoreSourceOpener = RestoreSourceOpener.CONTENT_RESOLVER,
    ): ByteDigest {
        faults.hit(RestoreFaultPoint.SOURCE_STREAM_READ)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        sourceOpener.open(context, uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    count += read
                }
                output.fd.sync()
            }
        } ?: error("Could not read the selected backup")
        return ByteDigest(
            count,
            digest.digest().joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    internal fun ensureSpace(
        dir: File,
        liveSize: Long,
        candidateSize: Long,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
        availableBytes: Long = StatFs(dir.path).availableBytes,
    ) {
        faults.hit(RestoreFaultPoint.FREE_SPACE_PREFLIGHT)
        check(availableBytes >= RestoreStorage.requiredBytes(liveSize, candidateSize)) {
            "Not enough internal storage to restore safely"
        }
    }

    private fun validateInstalled(live: File, state: State) {
        check(live.isFile && live.length() == state.candidateSize) {
            "Installed database size does not match candidate"
        }
        check(BackupHashing.hash(live).sha256 == state.candidateSha256) {
            "Installed database hash does not match candidate"
        }
        val result = BackupSnapshotValidator.validate(live, migrationSnapshot = false)
        check(result.schema.userVersion == state.candidateVersion)
        check(result.schema.userVersion == SynthXDatabase.VERSION) {
            "Installed database is not current"
        }
    }

    private fun validateOld(file: File, state: State): Boolean {
        if (!validateStandalone(file, currentOnly = true)) return false
        val size = state.oldSize
        val sha = state.oldSha256
        return if (size != null && sha != null) {
            file.length() == size && BackupHashing.hash(file).sha256 == sha
        } else true
    }

    private fun validateStandalone(file: File, currentOnly: Boolean): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        val result = BackupSnapshotValidator.validate(file, migrationSnapshot = !currentOnly)
        !currentOnly || result.schema.userVersion == SynthXDatabase.VERSION
    }.getOrDefault(false)

    private fun validateSafety(file: File, state: State): Boolean =
        file.isFile &&
            file.length() == state.safetySize &&
            BackupHashing.hash(file).sha256 == state.safetySha256 &&
            validateStandalone(file, currentOnly = true)

    private fun force(file: File) = RandomAccessFile(file, "rw").channel.use { it.force(true) }

    private fun rename(
        from: File,
        to: File,
        faultPoint: RestoreFaultPoint,
        faults: RestoreFaultInjector,
    ) {
        faults.hit(faultPoint)
        check(from.isFile) { "Missing ${from.name}" }
        check(!to.exists()) { "Unexpected ${to.name}" }
        try {
            Os.rename(from.path, to.path)
        } catch (error: ErrnoException) {
            throw IllegalStateException("Could not rename ${from.name}", error)
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

    private fun parkLiveIfPresent(dir: File, live: File, preferredName: String) {
        if (!live.exists()) return
        var failed = File(dir, preferredName)
        var index = 1
        while (failed.exists()) {
            failed = File(dir, preferredName.removeSuffix(".db") + "-$index.db")
            index++
        }
        rawRename(live, failed)
        detachSidecars(live, failed)
        syncDirectory(dir)
    }

    private fun quarantineSidecars(live: File, old: File) = detachSidecars(live, old)

    private fun detachSidecars(source: File, target: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(source.path + suffix)
            if (sidecar.exists()) rawRename(sidecar, File(target.path + suffix))
        }
    }

    private fun writeState(
        dir: File,
        state: State,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    ) {
        validateState(state)
        faults.hit(RestoreFaultPoint.STATE_PERSISTENCE)
        val temp = File(dir, STATE_TEMP_FILE)
        val target = File(dir, STATE_FILE)
        FileOutputStream(temp).use { output ->
            output.write(encodeState(state).encodeToByteArray())
            output.fd.sync()
        }
        try {
            Os.rename(temp.path, target.path)
        } catch (error: ErrnoException) {
            throw IllegalStateException("Could not atomically promote restore state", error)
        }
        syncDirectory(dir)
    }

    internal fun encodeState(state: State): String {
        validateState(state)
        return json.encodeToString(State.serializer(), state)
    }

    internal fun decodeStateOrNull(encoded: String): State? =
        runCatching { decodeAndValidateState(encoded) }.getOrNull()

    private fun decodeAndValidateState(encoded: String): State =
        json.decodeFromString(State.serializer(), encoded).also(::validateState)

    private fun validateState(state: State) {
        val names = RestoreArtifactNames(state.operationId)
        require(state.candidateName == names.candidate)
        require(state.oldName == names.old)
        require(state.safetyName == names.safety)
        require(state.failedName == names.failed)
        require(state.candidateSha256.matches(Regex("[0-9a-f]{64}")))
        require(state.safetySha256.matches(Regex("[0-9a-f]{64}")))
        require(state.candidateSize > 0 && state.safetySize > 0)
        require(state.candidateVersion == SynthXDatabase.VERSION)
        require(state.oldSha256 == null || state.oldSha256.matches(Regex("[0-9a-f]{64}")))
        require(state.oldSize == null || state.oldSize > 0)
        require(state.oldVersion == null || state.oldVersion == SynthXDatabase.VERSION)
    }

    private fun cleanupPreClose(dir: File, state: State) {
        deleteFileSet(File(dir, state.candidateName))
        deleteFileSet(File(dir, state.safetyName))
        File(dir, STATE_FILE).delete()
        File(dir, STATE_TEMP_FILE).delete()
        syncDirectory(dir)
    }

    private fun cleanupAfterColdValidation(dir: File, state: State) {
        // candidate was checkpoint-proven, old sidecars were detached only after full
        // checkpoint proof, and safety is a standalone VACUUM snapshot. Failed/unknown
        // copies are retained for diagnostics rather than having their WAL deleted blind.
        listOf(state.candidateName, state.oldName, state.safetyName).forEach {
            deleteFileSet(File(dir, it))
        }
        syncDirectory(dir)
    }

    private fun archiveRecoveredArtifacts(artifacts: List<File>) {
        artifacts.filter(File::exists).forEach { artifact ->
            var archive = File(artifact.path + ".recovered")
            var index = 1
            while (archive.exists()) {
                archive = File(artifact.path + ".recovered-$index")
                index++
            }
            rawRename(artifact, archive)
        }
    }

    private fun deleteFileSet(file: File) {
        listOf(
            file,
            File(file.path + "-journal"),
            File(file.path + "-wal"),
            File(file.path + "-shm"),
            File(file.path + ".checkpointed-journal"),
            File(file.path + ".checkpointed-wal"),
            File(file.path + ".checkpointed-shm"),
        ).forEach { it.delete() }
    }

    private fun archiveCorruptState(dir: File, operationId: String) {
        val state = File(dir, STATE_FILE)
        if (!state.exists()) return
        var archive = File(dir, "synthx.restore-state.corrupt-$operationId.json")
        var index = 1
        while (archive.exists()) {
            archive = File(dir, "synthx.restore-state.corrupt-$operationId-$index.json")
            index++
        }
        rawRename(state, archive)
        File(dir, STATE_TEMP_FILE).delete()
        syncDirectory(dir)
    }

    private fun stableIncidentId(dir: File, preferred: String?): String {
        if (!preferred.isNullOrBlank()) return preferred
        val file = File(dir, INCIDENT_FILE)
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (existing?.matches(Regex("recovery-[0-9a-f]{16}")) == true) return existing
        val generated = "recovery-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val temp = File(dir, "$INCIDENT_FILE.tmp")
        FileOutputStream(temp).use { output ->
            output.write(generated.encodeToByteArray())
            output.fd.sync()
        }
        if (file.exists()) {
            var archive = File(dir, "$INCIDENT_FILE.invalid")
            var index = 1
            while (archive.exists()) {
                archive = File(dir, "$INCIDENT_FILE.invalid-$index")
                index++
            }
            rawRename(file, archive)
        }
        rawRename(temp, file)
        syncDirectory(dir)
        return generated
    }

    private fun clearIncident(dir: File) {
        File(dir, INCIDENT_FILE).delete()
        File(dir, "$INCIDENT_FILE.tmp").delete()
        lastDiagnosticOperationId = null
    }

    fun diagnosticOperationId(): String? = lastDiagnosticOperationId

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

internal enum class RestoreRecovery {
    NORMAL,
    RESTART_REQUIRED,
    ROLLED_BACK_RESTART_REQUIRED,
    EMERGENCY,
}
