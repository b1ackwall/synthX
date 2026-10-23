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
    private const val TAG = "RestoreTransaction"
    private const val STATE_FILE = "synthx.restore-state.json"
    private const val STATE_TEMP_FILE = "synthx.restore-state.tmp"
    private const val INCIDENT_FILE = "synthx.restore-incident.id"
    private const val COMPLETED_REQUEST_LIMIT = 64
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    @Volatile private var lastDiagnosticOperationId: String? = null

    @Volatile private var lastRecoveryDecision: RecoveryDecisionSnapshot? = null

    private data class RecoveryDecisionSnapshot(val facts: String, val action: String)

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

    /** Explicit recovery path for the terminal UI. It never opens live Room or normal admission. */
    suspend fun executeEmergencyReplacement(
        context: Context,
        backup: BackupInfo,
        metadata: BackupMetadata,
        requestId: String,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
        sourceOpener: RestoreSourceOpener = RestoreSourceOpener.CONTENT_RESOLVER,
    ): Result<Unit> = RestoreExecutionCoordinator.serialize {
        runCatching {
            check(RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.EMERGENCY) {
                "Emergency replacement is available only while database recovery is locked"
            }
            when (RestoreEligibility.assess(metadata, backup.fileName)) {
                RestoreEligibility.TRUSTED_CURRENT,
                RestoreEligibility.TRUSTED_OLDER_SUPPORTED,
                -> Unit

                else -> error("Selected backup is not eligible for emergency recovery")
            }

            val op = normalizeRequestId(requestId)
            val dir = context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile
                ?: error("Database directory is unavailable")
            check(dir.isDirectory || dir.mkdirs()) { "Database directory is unavailable" }
            val completed = readCompletedRequestIds(dir)
            if (op in completed) return@runCatching

            val names = RestoreArtifactNames(op)
            val candidate = File(dir, names.candidate)
            val safety = File(dir, names.safety)
            val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
            val expectedSize = requireNotNull(metadata.sizeBytes)
            ensureSpace(dir, live.length(), expectedSize, faults)

            try {
                val sourceDigest = copySafOnce(
                    context,
                    backup.databaseUri.toString(),
                    candidate,
                    faults,
                    sourceOpener,
                    expectedSize,
                )
                require(sourceDigest.sizeBytes == expectedSize && sourceDigest.sha256 == metadata.sha256) {
                    "Backup bytes do not match verified metadata"
                }
                val sourceValidated = BackupSnapshotValidator.validate(candidate, migrationSnapshot = true)
                require(sourceValidated.schema.userVersion == metadata.dbVersion)
                if (sourceValidated.schema.userVersion < SynthXDatabase.VERSION) {
                    faults.hit(RestoreFaultPoint.MIGRATION)
                    migrateCandidate(context, candidate.name)
                }
                proveCandidateStandalone(candidate)
                val finalValidated = BackupSnapshotValidator.validate(candidate, migrationSnapshot = false)
                val candidateDigest = BackupHashing.hash(candidate)
                force(candidate)
                ensureSpace(dir, live.length(), candidateDigest.sizeBytes, faults)

                candidate.inputStream().use { input ->
                    FileOutputStream(safety).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                val safetyDigest = BackupHashing.hash(safety)
                require(safetyDigest == candidateDigest) { "Emergency fallback copy changed while staging" }
                BackupSnapshotValidator.validate(safety, migrationSnapshot = false)

                var state = State(
                    operationId = op,
                    requestId = op,
                    completedRequestIds = completed,
                    sourceName = backup.fileName,
                    sourceUri = "redacted:saf",
                    candidateName = names.candidate,
                    oldName = names.old,
                    safetyName = names.safety,
                    failedName = names.failed,
                    candidateSha256 = candidateDigest.sha256,
                    candidateSize = candidateDigest.sizeBytes,
                    candidateVersion = finalValidated.schema.userVersion,
                    safetySha256 = safetyDigest.sha256,
                    safetySize = safetyDigest.sizeBytes,
                    phase = Phase.PREPARED,
                    createdAt = System.currentTimeMillis(),
                )
                writeState(dir, state, faults)
                state = state.copy(phase = Phase.LIVE_MOVE_INTENT, updatedAt = System.currentTimeMillis())
                writeState(dir, state, faults)
                parkLiveIfPresent(dir, live, state.failedName, faults)
                state = state.copy(phase = Phase.LIVE_MOVED, updatedAt = System.currentTimeMillis())
                writeState(dir, state, faults)
                state = state.copy(phase = Phase.INSTALL_INTENT, updatedAt = System.currentTimeMillis())
                writeState(dir, state, faults)
                rename(candidate, live, RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME, faults)
                syncDirectory(dir, RestoreFaultPoint.CANDIDATE_TO_LIVE_DIRECTORY_SYNC, faults)
                state = state.copy(phase = Phase.CANDIDATE_INSTALLED, updatedAt = System.currentTimeMillis())
                writeState(dir, state, faults)
                validateInstalled(live, state)
                state = state.copy(phase = Phase.POST_VALIDATED, updatedAt = System.currentTimeMillis())
                writeState(dir, state, faults)
                RestoreCoordinator.markRestartRequired(rolledBack = false)
            } catch (failure: Throwable) {
                val journalOwnedByAttempt = File(dir, STATE_FILE).takeIf(File::isFile)
                    ?.let { runCatching { decodeAndValidateState(it.readText()) }.getOrNull() }
                    ?.operationId == op
                if (!journalOwnedByAttempt) {
                    deleteFileSet(candidate)
                    deleteFileSet(safety)
                }
                publishEmergencyIncident(
                    context,
                    dir,
                    op,
                    failure,
                    faults,
                    "EMERGENCY_REPLACEMENT_FAILURE",
                )
                throw failure
            }
        }
    }

    /** Runs before any normal database entrypoint. State is evidence, never sole authority. */
    fun recoverIfNeeded(
        context: Context,
        dir: File,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    ): RestoreRecovery {
        lastDiagnosticOperationId = null
        lastRecoveryDecision = null
        return try {
            val stateFile = File(dir, STATE_FILE)
            val state = stateFile.takeIf(File::isFile)?.let { file ->
                runCatching { decodeAndValidateState(file.readText()) }.getOrNull()
            }
            val recovery = if (state == null) {
                recoverWithoutUsableState(context, dir, stateFile.isFile, faults)
            } else {
                recoverWithState(context, dir, state, faults)
            }
            if (recovery == RestoreRecovery.EMERGENCY) {
                publishEmergencyIncident(
                    context,
                    dir,
                    lastDiagnosticOperationId,
                    null,
                    faults,
                    "RECOVERY_PLANNER_EMERGENCY",
                )
            }
            recovery
        } catch (failure: Throwable) {
            val discoveredOperation = runCatching {
                dir.listFiles().orEmpty()
                    .mapNotNull { RestoreArtifactNames.parseOperationId(it.name) }
                    .toSet()
                    .singleOrNull()
            }.getOrNull()
            publishEmergencyIncident(
                context,
                dir,
                lastDiagnosticOperationId ?: discoveredOperation,
                failure,
                faults,
                "RECOVERY_EXCEPTION",
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
        if (state.phase == Phase.COMPLETE) {
            return recoverCompletedHistory(context, dir, state, faults)
        }

        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
        val old = File(dir, state.oldName)
        val safety = File(dir, state.safetyName)
        val candidate = File(dir, state.candidateName)
        val liveValid = validateStandalone(live, currentOnly = true)
        val facts = RestoreArtifactFacts(
            liveExists = live.isFile,
            liveValid = liveValid,
            liveIsCandidate = runCatching { validateInstalled(live, state) }.isSuccess,
            oldExists = old.isFile,
            oldValid = validateOld(old, state),
            safetyValid = validateSafety(safety, state),
            candidateExists = candidate.isFile,
            liveMatchesOld = validateOld(live, state),
            liveMatchesSafety = validateSafety(live, state),
        )

        val action = RestoreRecoveryPlanner.decide(
            state.phase,
            facts,
            state.rollbackSource,
        )
        lastRecoveryDecision = RecoveryDecisionSnapshot(facts.toString(), action.name)
        return when (action) {
            RestoreRecoveryAction.CLEAN_PRE_CLOSE_AND_NORMAL -> {
                cleanupPreClose(dir, state, faults)
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
                ).withCompletedRequest()
                writeState(dir, complete, faults)
                cleanupAfterColdValidation(context, dir, complete, faults)
                clearIncident(dir)
                markArtSweepPending(context)
                RestoreRecovery.NORMAL
            }

            RestoreRecoveryAction.ACCEPT_COMPLETE_HISTORY_AND_NORMAL ->
                error("COMPLETE history must be handled before active recovery planning")

            RestoreRecoveryAction.PROMOTE_OLD ->
                completeStartupRollback(context, dir, live, state, RollbackSource.OLD, faults)

            RestoreRecoveryAction.PROMOTE_SAFETY ->
                completeStartupRollback(context, dir, live, state, RollbackSource.SAFETY, faults)

            RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL -> {
                check(validateStandalone(live, currentOnly = true)) {
                    "Recovered live database did not validate"
                }
                val statelessRecovery = state.phase == Phase.STATELESS_RECOVERY_INTENT ||
                    state.phase == Phase.STATELESS_RECOVERED
                val complete = state.copy(
                    phase = Phase.COMPLETE,
                    outcome = Outcome.ROLLED_BACK,
                    updatedAt = System.currentTimeMillis(),
                ).withCompletedRequest()
                writeState(dir, complete, faults)
                if (statelessRecovery) {
                    archiveOperationArtifacts(dir, state.operationId, faults)
                } else {
                    cleanupAfterColdValidation(context, dir, complete, faults)
                }
                clearIncident(dir)
                RestoreRecovery.NORMAL
            }

            RestoreRecoveryAction.EMERGENCY -> RestoreRecovery.EMERGENCY
        }.also { result ->
            if (result == RestoreRecovery.EMERGENCY) lastDiagnosticOperationId = state.operationId
        }
    }

    /**
     * Flags a one-time art-recovery sweep for [MainActivity.runLaunchMaintenance] to consume on
     * this cold boot — see [PreferencesManager.artSweepPending]. Called only on the two
     * recovery paths that resolve a restore's outcome to installed, since a rolled-back or
     * otherwise non-installed outcome leaves the pre-restore library (and its art) untouched.
     */
    private fun markArtSweepPending(context: Context) {
        PreferencesManager(context).artSweepPending = true
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
            lastDiagnosticOperationId = stableIncidentId(dir, null, faults)
            return RestoreRecovery.EMERGENCY
        }

        val op = operationIds.single()
        lastDiagnosticOperationId = op
        val names = RestoreArtifactNames(op)
        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
        val old = File(dir, names.old)
        val safety = File(dir, names.safety)
        val oldValid = validateStandalone(old, currentOnly = true)
        val safetyValid = validateStandalone(safety, currentOnly = true)
        val liveValid = validateStandalone(live, currentOnly = true)
        val action = StatelessRecoveryPlanner.decide(
            operationCount = operationIds.size,
            liveValid = liveValid,
            oldValid = oldValid,
            safetyValid = safetyValid,
        )
        lastRecoveryDecision = RecoveryDecisionSnapshot(
            "operationCount=${operationIds.size}, liveValid=$liveValid, oldValid=$oldValid, safetyValid=$safetyValid",
            action.name,
        )
        return when (action) {
            StatelessRecoveryAction.KEEP_VALID_LIVE -> {
                if (malformedStateExists) archiveCorruptState(dir, op, faults)
                archiveRecoveredArtifacts(artifacts, dir, faults)
                clearIncident(dir)
                RestoreRecovery.NORMAL
            }

            StatelessRecoveryAction.PROMOTE_OLD,
            StatelessRecoveryAction.PROMOTE_SAFETY,
            -> {
                val rollbackSource = if (oldValid) RollbackSource.OLD else RollbackSource.SAFETY
                val source = if (rollbackSource == RollbackSource.OLD) old else safety
                if (malformedStateExists) archiveCorruptState(dir, op, faults)
                val state = createStatelessRecoveryState(
                    op = op,
                    names = names,
                    source = source,
                    rollbackSource = rollbackSource,
                )
                writeState(dir, state, faults)
                completeStartupRollback(context, dir, live, state, rollbackSource, faults)
            }

            StatelessRecoveryAction.EMERGENCY -> RestoreRecovery.EMERGENCY
        }
    }

    /**
     * COMPLETE is durable history, not authority over the live database. Once cleanup artifacts
     * are gone it must not impose restore-specific schema checks on later app versions; the normal
     * database startup gate owns version inspection and migration. If completion cleanup was
     * interrupted, resume only that idempotent cleanup without reopening recovery or inspecting
     * the live database.
     */
    private fun recoverCompletedHistory(
        context: Context,
        dir: File,
        state: State,
        faults: RestoreFaultInjector,
    ): RestoreRecovery {
        val artifactsRemain = dir.listFiles().orEmpty().any {
            RestoreArtifactNames.parseOperationId(it.name) == state.operationId
        }
        if (!artifactsRemain) {
            clearIncident(dir)
            return RestoreRecovery.NORMAL
        }

        cleanupAfterColdValidation(context, dir, state, faults)
        clearIncident(dir)
        if (state.outcome == Outcome.INSTALLED) markArtSweepPending(context)
        return RestoreRecovery.NORMAL
    }

    private fun createStatelessRecoveryState(
        op: String,
        names: RestoreArtifactNames,
        source: File,
        rollbackSource: RollbackSource,
    ): State {
        val sourceDigest = BackupHashing.hash(source)
        val candidate = File(source.parentFile, names.candidate)
        val candidateDigest = candidate.takeIf {
            validateStandalone(it, currentOnly = true)
        }?.let(BackupHashing::hash) ?: sourceDigest
        val safety = File(source.parentFile, names.safety)
        val safetyDigest = safety.takeIf {
            validateStandalone(it, currentOnly = true)
        }?.let(BackupHashing::hash) ?: sourceDigest
        val now = System.currentTimeMillis()
        return State(
            operationId = op,
            sourceName = "startup-recovery",
            sourceUri = "",
            candidateName = names.candidate,
            oldName = names.old,
            safetyName = names.safety,
            failedName = names.failed,
            candidateSha256 = candidateDigest.sha256,
            candidateSize = candidateDigest.sizeBytes,
            candidateVersion = SynthXDatabase.VERSION,
            safetySha256 = safetyDigest.sha256,
            safetySize = safetyDigest.sizeBytes,
            phase = Phase.STATELESS_RECOVERY_INTENT,
            createdAt = now,
            oldSha256 = sourceDigest.sha256.takeIf { rollbackSource == RollbackSource.OLD },
            oldSize = sourceDigest.sizeBytes.takeIf { rollbackSource == RollbackSource.OLD },
            oldVersion = SynthXDatabase.VERSION.takeIf { rollbackSource == RollbackSource.OLD },
            outcome = Outcome.ROLLED_BACK,
            rollbackSource = rollbackSource,
        )
    }

    private fun completeStartupRollback(
        context: Context,
        dir: File,
        live: File,
        original: State,
        rollbackSource: RollbackSource,
        faults: RestoreFaultInjector,
    ): RestoreRecovery {
        val statelessRecovery = original.phase == Phase.STATELESS_RECOVERY_INTENT ||
            original.phase == Phase.STATELESS_RECOVERED
        val source = File(
            dir,
            if (rollbackSource == RollbackSource.SAFETY) original.safetyName else original.oldName,
        )
        val stateWithSourceIdentity = if (
            rollbackSource == RollbackSource.OLD &&
            (original.oldSha256 == null || original.oldSize == null || original.oldVersion == null)
        ) {
            val digest = BackupHashing.hash(source)
            original.copy(
                oldSha256 = digest.sha256,
                oldSize = digest.sizeBytes,
                oldVersion = SynthXDatabase.VERSION,
            )
        } else {
            original
        }
        var state = stateWithSourceIdentity.copy(
            phase = if (statelessRecovery) Phase.STATELESS_RECOVERY_INTENT else Phase.ROLLBACK_INTENT,
            outcome = Outcome.ROLLED_BACK,
            rollbackSource = rollbackSource,
            updatedAt = System.currentTimeMillis(),
        )
        writeState(dir, state, faults)
        parkLiveIfPresent(dir, live, state.failedName, faults)
        rename(
            source,
            live,
            if (rollbackSource == RollbackSource.SAFETY) {
                RestoreFaultPoint.SAFETY_FALLBACK_PROMOTION
            } else {
                RestoreFaultPoint.OLD_ROLLBACK_RENAME
            },
            faults,
        )
        syncDirectory(
            dir,
            if (statelessRecovery) {
                RestoreFaultPoint.STATELESS_FALLBACK_DIRECTORY_SYNC
            } else {
                RestoreFaultPoint.STARTUP_ROLLBACK_DIRECTORY_SYNC
            },
            faults,
        )
        check(validateStandalone(live, currentOnly = true)) {
            "Startup rollback database validation failed"
        }
        state = state.copy(
            phase = if (statelessRecovery) Phase.STATELESS_RECOVERED else Phase.ROLLED_BACK,
            updatedAt = System.currentTimeMillis(),
        )
        writeState(dir, state, faults)
        state = state.copy(phase = Phase.COMPLETE, updatedAt = System.currentTimeMillis())
            .withCompletedRequest()
        writeState(dir, state, faults)
        if (statelessRecovery) {
            archiveOperationArtifacts(dir, state.operationId, faults)
        } else {
            cleanupAfterColdValidation(context, dir, state, faults)
        }
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

    internal fun writeStateForTest(dir: File, state: State) = writeState(dir, state)

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
        expectedSizeBytes: Long? = null,
        // A plain (non-suspend) poll rather than making this function itself suspend —
        // it is called from several non-coroutine test call sites (see
        // PhaseBRestoreAdmissionInstrumentedTest) that would otherwise need reworking. Callers
        // in a coroutine pass `{ job?.isActive == false }` for real, cooperative cancellation.
        isCancelled: () -> Boolean = { false },
    ): ByteDigest {
        faults.hit(RestoreFaultPoint.SOURCE_STREAM_READ)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        sourceOpener.open(context, uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) throw CancellationException("Backup copy cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (expectedSizeBytes != null && read.toLong() > expectedSizeBytes - count) {
                        error("Selected backup exceeds its declared size")
                    }
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
        } else {
            true
        }
    }

    private fun validateStandalone(file: File, currentOnly: Boolean): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        val result = BackupSnapshotValidator.validate(file, migrationSnapshot = !currentOnly)
        !currentOnly || result.schema.userVersion == SynthXDatabase.VERSION
    }.getOrDefault(false)

    private fun validateSafety(file: File, state: State): Boolean = file.isFile &&
        file.length() == state.safetySize &&
        BackupHashing.hash(file).sha256 == state.safetySha256 &&
        validateStandalone(file, currentOnly = true)

    private fun force(file: File) = RandomAccessFile(file, "rw").channel.use { it.force(true) }

    private fun rename(from: File, to: File, faultPoint: RestoreFaultPoint, faults: RestoreFaultInjector) {
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

    private fun parkLiveIfPresent(dir: File, live: File, preferredName: String, faults: RestoreFaultInjector) {
        if (!live.exists()) return
        var failed = File(dir, preferredName)
        var index = 1
        while (failed.exists()) {
            failed = File(dir, preferredName.removeSuffix(".db") + "-$index.db")
            index++
        }
        rawRename(live, failed)
        detachSidecars(live, failed)
        syncDirectory(dir, RestoreFaultPoint.PARK_LIVE_DIRECTORY_SYNC, faults)
    }

    private fun quarantineSidecars(live: File, old: File) = detachSidecars(live, old)

    private fun detachSidecars(source: File, target: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(source.path + suffix)
            if (sidecar.exists()) rawRename(sidecar, File(target.path + suffix))
        }
    }

    private fun writeState(dir: File, state: State, faults: RestoreFaultInjector = RestoreFaultInjector.NONE) {
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
        syncDirectory(dir, RestoreFaultPoint.STATE_JOURNAL_DIRECTORY_SYNC, faults)
    }

    internal fun encodeState(state: State): String {
        validateState(state)
        return json.encodeToString(State.serializer(), state)
    }

    internal fun decodeStateOrNullForTest(encoded: String): State? =
        runCatching { decodeAndValidateState(encoded) }.getOrNull()

    private fun decodeAndValidateState(encoded: String): State =
        json.decodeFromString(State.serializer(), encoded).also(::validateState)

    private fun validateState(state: State) {
        validateStateStructure(state)
        if (state.phase != Phase.COMPLETE) validateActiveStateVersion(state)
    }

    private fun validateStateStructure(state: State) {
        val names = RestoreArtifactNames(state.operationId)
        require(state.candidateName == names.candidate)
        require(state.oldName == names.old)
        require(state.safetyName == names.safety)
        require(state.failedName == names.failed)
        require(state.candidateSha256.matches(Regex("[0-9a-f]{64}")))
        require(state.safetySha256.matches(Regex("[0-9a-f]{64}")))
        require(state.candidateSize > 0 && state.safetySize > 0)
        require(state.candidateVersion > 0)
        require(state.oldSha256 == null || state.oldSha256.matches(Regex("[0-9a-f]{64}")))
        require(state.oldSize == null || state.oldSize > 0)
        require(state.oldVersion == null || state.oldVersion > 0)
        state.requestId?.let { require(normalizeRequestId(it) == it) }
        require(state.completedRequestIds.size <= COMPLETED_REQUEST_LIMIT)
        require(state.completedRequestIds.distinct().size == state.completedRequestIds.size)
        state.completedRequestIds.forEach { require(normalizeRequestId(it) == it) }
    }

    private fun State.withCompletedRequest(): State {
        val consumed = normalizeRequestId(requestId ?: operationId)
        return copy(
            completedRequestIds = (completedRequestIds.filterNot { it == consumed } + consumed)
                .takeLast(COMPLETED_REQUEST_LIMIT),
        )
    }

    private fun readCompletedRequestIds(dir: File): List<String> {
        val state = File(dir, STATE_FILE).takeIf(File::isFile)
            ?.let { runCatching { decodeAndValidateState(it.readText()) }.getOrNull() }
            ?: return emptyList()
        return state.completedRequestIds
    }

    private fun normalizeRequestId(value: String): String {
        val normalized = value.lowercase().replace("-", "")
        require(normalized.matches(Regex("[0-9a-f]{32}"))) { "Invalid restore request ID" }
        return normalized
    }

    private fun validateActiveStateVersion(state: State) {
        require(state.candidateVersion == SynthXDatabase.VERSION)
        require(state.oldVersion == null || state.oldVersion == SynthXDatabase.VERSION)
    }

    private fun cleanupPreClose(dir: File, state: State, faults: RestoreFaultInjector) {
        deleteFileSet(File(dir, state.candidateName))
        deleteFileSet(File(dir, state.safetyName))
        File(dir, STATE_FILE).delete()
        File(dir, STATE_TEMP_FILE).delete()
        syncDirectory(dir, RestoreFaultPoint.PRE_CLOSE_CLEANUP_DIRECTORY_SYNC, faults)
    }

    private fun cleanupAfterColdValidation(context: Context, dir: File, state: State, faults: RestoreFaultInjector) {
        // Only a successful install's safety snapshot is worth keeping around as a
        // bonus Auto-tier backup — a rolled-back attempt's safety copy either already became
        // live via completeStartupRollback/rollbackCurrentProcess, or is just ordinary
        // transaction debris with nothing new to preserve. Runs strictly here: this function
        // is only ever reached once Phase.COMPLETE has already been durably written above, so
        // the safety file has been untouched (available for rollback) through every phase up
        // to this point.
        if (state.outcome == Outcome.INSTALLED) {
            SafetySnapshotPromoter.promoteSafetyToAutoTier(context, dir, state, faults)
        }
        // candidate was checkpoint-proven, old sidecars were detached only after full
        // checkpoint proof, and safety is a standalone VACUUM snapshot. Failed/unknown
        // copies are retained for diagnostics rather than having their WAL deleted blind.
        listOf(state.candidateName, state.oldName, state.safetyName).forEach {
            deleteFileSet(File(dir, it))
        }
        syncDirectory(dir, RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC, faults)
    }

    private fun archiveOperationArtifacts(dir: File, operationId: String, faults: RestoreFaultInjector) {
        archiveRecoveredArtifacts(
            dir.listFiles().orEmpty().filter {
                RestoreArtifactNames.parseOperationId(it.name) == operationId
            },
            dir,
            faults,
        )
    }

    private fun archiveRecoveredArtifacts(artifacts: List<File>, dir: File, faults: RestoreFaultInjector) {
        var renamed = false
        artifacts.filter(File::exists).forEach { artifact ->
            var archive = File(artifact.path + ".recovered")
            var index = 1
            while (archive.exists()) {
                archive = File(artifact.path + ".recovered-$index")
                index++
            }
            rawRename(artifact, archive)
            renamed = true
        }
        if (renamed) {
            syncDirectory(
                dir,
                RestoreFaultPoint.RECOVERED_ARTIFACT_ARCHIVE_DIRECTORY_SYNC,
                faults,
            )
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

    private fun archiveCorruptState(dir: File, operationId: String, faults: RestoreFaultInjector) {
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
        syncDirectory(dir, RestoreFaultPoint.CORRUPT_STATE_ARCHIVE_DIRECTORY_SYNC, faults)
    }

    private fun stableIncidentId(dir: File, preferred: String?, faults: RestoreFaultInjector): String {
        val fallback = preferred?.takeIf { it.isNotBlank() }
            ?: "recovery-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val file = File(dir, INCIDENT_FILE)
        val existing = runCatching { file.readText().trim() }.getOrNull()
            ?.takeIf { it.matches(Regex("(?:recovery-[0-9a-f]{16}|[0-9a-f]{32})")) }
        val selected = preferred?.takeIf { it.isNotBlank() } ?: existing ?: fallback
        runCatching {
            val temp = File(dir, "$INCIDENT_FILE.tmp")
            faults.hit(RestoreFaultPoint.INCIDENT_ID_OPEN)
            FileOutputStream(temp).use { output ->
                faults.hit(RestoreFaultPoint.INCIDENT_ID_WRITE)
                output.write(selected.encodeToByteArray())
                faults.hit(RestoreFaultPoint.INCIDENT_ID_FILE_SYNC)
                output.fd.sync()
            }
            if (file.exists() && existing == null) {
                var archive = File(dir, "$INCIDENT_FILE.invalid")
                var index = 1
                while (archive.exists()) {
                    archive = File(dir, "$INCIDENT_FILE.invalid-$index")
                    index++
                }
                faults.hit(RestoreFaultPoint.INCIDENT_ID_ARCHIVE_RENAME)
                rawRename(file, archive)
            }
            faults.hit(RestoreFaultPoint.INCIDENT_ID_PROMOTE_RENAME)
            rawRename(temp, file)
            syncDirectory(dir, RestoreFaultPoint.INCIDENT_DIRECTORY_SYNC, faults)
        }.onFailure { error ->
            Log.e(TAG, "Could not persist restore incident ID $selected", error)
        }
        return selected
    }

    private fun publishEmergencyIncident(
        context: Context,
        dir: File,
        preferred: String?,
        failure: Throwable?,
        faults: RestoreFaultInjector,
        triggerCategory: String,
    ): String {
        val incidentId = stableIncidentId(dir, preferred, faults)
        lastDiagnosticOperationId = incidentId
        runCatching {
            val state = File(dir, STATE_FILE).takeIf(File::isFile)
                ?.let { runCatching { decodeAndValidateState(it.readText()) }.getOrNull() }
            val report = RestoreIncidentReports.build(
                context = context,
                incidentId = incidentId,
                state = state,
                failure = failure,
                artifacts = incidentArtifacts(context, dir),
                plannerFacts = lastRecoveryDecision?.facts,
                selectedAction = lastRecoveryDecision?.action,
                triggerCategory = triggerCategory,
            )
            RestoreIncidentReports.writeBestEffort(context, report, faults)
        }.onFailure { Log.e(TAG, "Could not construct restore incident report $incidentId", it) }
        return incidentId
    }

    internal fun publishUnexpectedStartupIncident(context: Context, failure: Throwable): String {
        val dir = context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile
        if (dir == null) {
            val fallback = "recovery-" + UUID.randomUUID().toString().replace("-", "").take(16)
            lastDiagnosticOperationId = fallback
            return fallback
        }
        return publishEmergencyIncident(
            context,
            dir,
            lastDiagnosticOperationId,
            failure,
            RestoreFaultInjector.NONE,
            "STARTUP_BOUNDARY_EXCEPTION",
        )
    }

    private fun incidentArtifacts(context: Context, dir: File): List<RestoreIncidentArtifact> {
        val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
        val files = (
            dir.listFiles().orEmpty().filter {
                RestoreArtifactNames.parseOperationId(it.name) != null ||
                    it.name == STATE_FILE || it.name.startsWith(INCIDENT_FILE)
            } + live
            ).distinctBy { it.absolutePath }
        return files.map { file ->
            val validation = if (!file.isFile || !file.name.endsWith(".db")) {
                "not_applicable"
            } else {
                runCatching { if (validateStandalone(file, currentOnly = false)) "valid" else "invalid" }
                    .getOrElse { "error:${it.javaClass.simpleName}:${it.message.orEmpty()}" }
            }
            RestoreIncidentArtifact(file.name, file.exists(), file.length(), validation)
        }
    }

    private fun clearIncident(dir: File) {
        File(dir, INCIDENT_FILE).delete()
        File(dir, "$INCIDENT_FILE.tmp").delete()
        lastDiagnosticOperationId = null
    }

    fun diagnosticOperationId(): String? = lastDiagnosticOperationId

    private fun syncDirectory(dir: File, faultPoint: RestoreFaultPoint, faults: RestoreFaultInjector) {
        faults.hit(faultPoint)
        val descriptor = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        var primaryFailure: Throwable? = null
        try {
            Os.fsync(descriptor)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                Os.close(descriptor)
            } catch (closeError: Throwable) {
                val fsyncFailure = primaryFailure
                if (fsyncFailure != null) fsyncFailure.addSuppressed(closeError) else throw closeError
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
