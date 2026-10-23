package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.work.WorkManager
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.repository.RepositoryDbFlow
import com.v1per.synthx.playback.ExoPlayerEngine
import com.v1per.synthx.playback.QueuePersistence
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// NOTE: This file intentionally exceeds the project's 500-line
// guideline. Its separable pieces live elsewhere: the pure phase-recovery decision
// table as RestoreRecoveryPlanner (RestorePrimitives.kt), and the safety-snapshot
// promotion as SafetySnapshotPromoter.kt.
// What remains is the crash-safety core: execute(), recoverIfNeeded /
// recoverWithState / recoverWithoutUsableState, completeStartupRollback /
// rollbackCurrentProcess, checkpointCloseAndProveMain, and writeState's state
// persistence. That core alone is over the 500-line target, so the target is not
// reachable here without cutting into the core itself.
//
// Cutting into it has two real costs. Extracting the rollback pair alone would
// require widening several of this file's private FS/state primitives plus an
// accessor for lastDiagnosticOperationId — an encapsulation loss, not a
// line-count nicety. And this file's guarantee is durability ordering: every
// recovery-critical rename is immediately followed by its own syncDirectory,
// with nothing able to interleave. Every directory-sync fault point has a device
// test that injects it by name (RestoreFaultPointCoverageTest enforces the naming).
// Treat this as a deliberate exception, not an oversight.
/**
 * Coarse, UI-facing progress reported through [RestoreTransaction.execute]'s
 * `onProgress` callback. Deliberately public and top-level, rather than nested inside
 * [RestoreTransaction] (which is `internal`) — [BackupManager.previewBackup]/`restore` and
 * [com.v1per.synthx.ui.screens.restore.RestoreViewModel] surface it on their own public API,
 * and a type nested in an internal container can't appear there. Also a deliberately separate,
 * small enum rather than exposing [RestoreTransaction.Phase] itself — [RestoreTransaction.Phase]
 * is durable, versioned journal state; this is ephemeral and carries no persistence contract, so
 * it can be freely reshaped without touching the crash-recovery format. Only reported for the
 * interactive [RestoreTransaction.execute] path, not [RestoreTransaction.executeEmergencyReplacement]
 * or the startup recovery functions, since only `execute` is ever driven by a live worker with a
 * UI observer.
 */
enum class RestoreProgressStep { COPYING, VALIDATING, INSTALLING, FINISHING }

/** The durable, on-disk boundary of a restore. All database artifacts are same-directory. */
internal object RestoreTransaction {
    internal const val STATE_FILE = "synthx.restore-state.json"
    internal const val STATE_TEMP_FILE = "synthx.restore-state.tmp"
    internal const val COMPLETED_REQUEST_LIMIT = 64

    @Volatile internal var lastDiagnosticOperationId: String? = null

    @Volatile internal var lastRecoveryDecision: RecoveryDecisionSnapshot? = null

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
        STATELESS_RECOVERY_INTENT,
        STATELESS_RECOVERED,
        COMPLETE,
    }

    @Serializable
    enum class Outcome { INSTALLED, ROLLED_BACK }

    @Serializable
    enum class RollbackSource { OLD, SAFETY }

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
        val rollbackSource: RollbackSource? = null,
        /** WorkManager UUID without hyphens. Null only for journals written by older builds. */
        val requestId: String? = null,
        /** Bounded replay tombstones, carried into every subsequent journal generation. */
        val completedRequestIds: List<String> = emptyList(),
    )

    class PostCloseFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    suspend fun execute(
        context: Context,
        backup: BackupInfo,
        metadata: BackupMetadata,
        requestId: String,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
        sourceOpener: RestoreSourceOpener = RestoreSourceOpener.CONTENT_RESOLVER,
        onProgress: suspend (RestoreProgressStep) -> Unit = {},
    ): Result<Unit> = RestoreExecutionCoordinator.serialize {
        runCatching {
            when (RestoreEligibility.assess(metadata, backup.fileName)) {
                RestoreEligibility.TRUSTED_CURRENT,
                RestoreEligibility.TRUSTED_OLDER_SUPPORTED,
                -> Unit

                RestoreEligibility.NEWER_SCHEMA -> error(
                    "This backup was created by a newer version of synthX and cannot be restored with this version.",
                )

                RestoreEligibility.UNSUPPORTED_SCHEMA -> error(
                    "No supported migration path exists for this backup",
                )

                RestoreEligibility.LEGACY_OR_MALFORMED -> error(
                    "Legacy or malformed backup metadata cannot be restored",
                )
            }

            val normalizedRequestId = normalizeRequestId(requestId)
            val dir = context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile
                ?: error("Database directory is unavailable")
            check(dir.isDirectory || dir.mkdirs()) { "Database directory is unavailable" }
            check(recoverIfNeeded(context, dir, faults) == RestoreRecovery.NORMAL) {
                "An existing restore transaction requires recovery before another restore can start"
            }
            RestoreCoordinator.requireNormal()

            val completedRequestIds = readCompletedRequestIds(dir)
            if (normalizedRequestId in completedRequestIds) return@runCatching

            val op = normalizedRequestId
            val names = RestoreArtifactNames(op)
            val candidate = File(dir, names.candidate)
            val old = File(dir, names.old)
            val safety = File(dir, names.safety)
            val failed = File(dir, names.failed)
            val live = context.getDatabasePath(SynthXDatabase.DB_NAME)

            try {
                val expectedSourceSize = requireNotNull(metadata.sizeBytes)
                ensureSpace(dir, live.length(), expectedSourceSize, faults)
                onProgress(RestoreProgressStep.COPYING)
                // Cancellable only up to here: the copy touches nothing but the candidate file, so
                // a cancel request can stop it cleanly. The Job is read once, outside the plain
                // (non-suspend) isCancelled lambda copySafOnce polls internally.
                val copyJob = currentCoroutineContext()[Job]
                val sourceDigest = copySafOnce(
                    context,
                    backup.databaseUri.toString(),
                    candidate,
                    faults,
                    sourceOpener,
                    expectedSourceSize,
                    isCancelled = { copyJob?.isActive == false },
                )
                faults.hit(RestoreFaultPoint.SOURCE_SHA_VERIFY)
                require(
                    sourceDigest.sizeBytes == metadata.sizeBytes &&
                        sourceDigest.sha256 == metadata.sha256,
                ) { "Backup bytes do not match its verified size and SHA-256 metadata" }

                onProgress(RestoreProgressStep.VALIDATING)
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

                // NonCancellable from here on: once admission is closed and the safety snapshot
                // starts, a cancel request (e.g. from a UI cancel button on the enclosing Worker)
                // must not interrupt the live-swap sequence — see RestoreProgressStep's doc.
                // Cancellation is only ever honored earlier, during the plain copy above.
                withContext(NonCancellable) {
                    RestoreCoordinator.exclusive {
                        onProgress(RestoreProgressStep.INSTALLING)
                        awaitScanIdle(context)
                        ensureSpace(dir, live.length(), candidateDigest.sizeBytes, faults)

                        // Admission is already closed. Let every operation accepted before that
                        // boundary finish so the mandatory safety snapshot includes all of them.
                        RestoreCoordinator.awaitFiniteDrained()

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
                            requestId = normalizedRequestId,
                            completedRequestIds = completedRequestIds,
                        )
                        writeState(dir, state, faults)

                        var permanentQuiesceStarted = false
                        try {
                            permanentQuiesceStarted = true
                            ExoPlayerEngine.releaseAllForRestore()
                            QueuePersistence.quiesceAllForRestore()
                            RepositoryDbFlow.quiesceForRestore()
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
                            syncDirectory(dir, RestoreFaultPoint.LIVE_TO_OLD_DIRECTORY_SYNC, faults)
                            state = state.copy(phase = Phase.LIVE_MOVED, updatedAt = System.currentTimeMillis())
                            writeState(dir, state, faults)

                            state = state.copy(phase = Phase.INSTALL_INTENT, updatedAt = System.currentTimeMillis())
                            writeState(dir, state, faults)
                            rename(candidate, live, RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME, faults)
                            syncDirectory(dir, RestoreFaultPoint.CANDIDATE_TO_LIVE_DIRECTORY_SYNC, faults)
                            state =
                                state.copy(phase = Phase.CANDIDATE_INSTALLED, updatedAt = System.currentTimeMillis())
                            writeState(dir, state, faults)

                            faults.hit(RestoreFaultPoint.INSTALLED_VALIDATION)
                            validateInstalled(live, state)
                            state = state.copy(phase = Phase.POST_VALIDATED, updatedAt = System.currentTimeMillis())
                            writeState(dir, state, faults)
                            onProgress(RestoreProgressStep.FINISHING)
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
                                val incidentId = publishEmergencyIncident(
                                    context,
                                    dir,
                                    op,
                                    rollbackFailure,
                                    faults,
                                    "CURRENT_PROCESS_ROLLBACK_FAILURE",
                                )
                                RestoreCoordinator.markEmergency(incidentId)
                                throw PostCloseFailure(
                                    "Automatic rollback could not validate old or safety database",
                                    rollbackFailure,
                                )
                            }
                            throw PostCloseFailure("Restore was rolled back; close and reopen synthX", failure)
                        }
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
                        try {
                            syncDirectory(
                                dir,
                                RestoreFaultPoint.PRE_CLOSE_FAILURE_CLEANUP_DIRECTORY_SYNC,
                                faults,
                            )
                            RestoreCoordinator.markNormal()
                        } catch (syncFailure: Exception) {
                            failure.addSuppressed(syncFailure)
                            val incidentId = publishEmergencyIncident(
                                context,
                                dir,
                                op,
                                failure,
                                faults,
                                "PRE_CLOSE_CLEANUP_DURABILITY_FAILURE",
                            )
                            RestoreCoordinator.markEmergency(incidentId)
                            throw PostCloseFailure(
                                "Restore cleanup could not be made durable; close and reopen synthX",
                                failure,
                            )
                        }
                    }

                    else -> Unit
                }
                throw failure
            }
        }
    }

    private fun rollbackCurrentProcess(
        dir: File,
        live: File,
        original: State,
        cause: Exception,
        faults: RestoreFaultInjector,
    ) {
        val old = File(dir, original.oldName)
        val safety = File(dir, original.safetyName)
        // SQLite's corruption handler may rename an invalid database to *.db.corrupt
        // while validateOld is running. Preserve the pre-validation fact so that rename
        // cannot be mistaken for "live->old never happened".
        val oldWasPresent = old.isFile
        val oldIsValid = validateOld(old, original)
        val liveIsOld = !oldWasPresent && validateOld(live, original)
        val safetyIsValid = validateSafety(safety, original)
        val rollbackSource = when {
            oldIsValid || liveIsOld -> RollbackSource.OLD

            safetyIsValid -> RollbackSource.SAFETY

            else -> throw IllegalStateException(
                "Automatic rollback could not validate old or safety database",
                cause,
            )
        }
        var state = original.copy(
            phase = Phase.ROLLBACK_INTENT,
            outcome = Outcome.ROLLED_BACK,
            rollbackSource = rollbackSource,
            updatedAt = System.currentTimeMillis(),
        )
        writeState(dir, state, faults)
        when (rollbackSource) {
            RollbackSource.OLD -> if (oldIsValid) {
                parkLiveIfPresent(dir, live, state.failedName, faults)
                rename(old, live, RestoreFaultPoint.OLD_ROLLBACK_RENAME, faults)
            }

            RollbackSource.SAFETY -> {
                parkLiveIfPresent(dir, live, state.failedName, faults)
                rename(safety, live, RestoreFaultPoint.SAFETY_FALLBACK_PROMOTION, faults)
            }
        }
        syncDirectory(dir, RestoreFaultPoint.CURRENT_ROLLBACK_DIRECTORY_SYNC, faults)
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

    private suspend fun awaitScanIdle(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME)
        workManager.getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
            .first { infos -> infos.all { it.state.isFinished } }
    }

    internal fun State.withCompletedRequest(): State {
        val consumed = normalizeRequestId(requestId ?: operationId)
        return copy(
            completedRequestIds = (completedRequestIds.filterNot { it == consumed } + consumed)
                .takeLast(COMPLETED_REQUEST_LIMIT),
        )
    }

    internal fun normalizeRequestId(value: String): String {
        val normalized = value.lowercase().replace("-", "")
        require(normalized.matches(Regex("[0-9a-f]{32}"))) { "Invalid restore request ID" }
        return normalized
    }
}

internal enum class RestoreRecovery {
    NORMAL,
    RESTART_REQUIRED,
    ROLLED_BACK_RESTART_REQUIRED,
    EMERGENCY,
}
