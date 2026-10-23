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

    private suspend fun awaitScanIdle(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME)
        workManager.getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
            .first { infos -> infos.all { it.state.isFinished } }
    }

    private fun State.withCompletedRequest(): State {
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
