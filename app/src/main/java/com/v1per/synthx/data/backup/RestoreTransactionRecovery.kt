package com.v1per.synthx.data.backup

import android.content.Context
import android.util.Log
import com.v1per.synthx.data.backup.RestoreTransaction.Outcome
import com.v1per.synthx.data.backup.RestoreTransaction.Phase
import com.v1per.synthx.data.backup.RestoreTransaction.RollbackSource
import com.v1per.synthx.data.backup.RestoreTransaction.State
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

// RestoreTransaction's startup recovery: journal-driven and stateless recovery, completed history, startup rollback.

private const val TAG = "RestoreTransaction"

/** Runs before any normal database entrypoint. State is evidence, never sole authority. */
internal fun RestoreTransaction.recoverIfNeeded(
    context: Context,
    dir: File,
    faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    statelessPlanner: StatelessPlan = StatelessRecoveryPlanner::decide,
): RestoreRecovery {
    lastDiagnosticOperationId = null
    lastRecoveryDecision = null
    return try {
        val stateFile = File(dir, STATE_FILE)
        val state = stateFile.takeIf(File::isFile)?.let { file ->
            runCatching { decodeAndValidateState(file.readText()) }.getOrNull()
        }
        val recovery = if (state == null) {
            recoverWithoutUsableState(context, dir, stateFile.isFile, faults, statelessPlanner)
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

private fun RestoreTransaction.recoverWithState(
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
            markArtSweepPending(context)
            if (cleanupCompletedRestore(context, dir, complete, faults)) clearIncident(dir)
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
private fun RestoreTransaction.markArtSweepPending(context: Context) {
    PreferencesManager(context).artSweepPending = true
}

/** Missing/malformed state may touch only filenames owned by synthX restore operations. */
private fun RestoreTransaction.recoverWithoutUsableState(
    context: Context,
    dir: File,
    malformedStateExists: Boolean,
    faults: RestoreFaultInjector,
    statelessPlanner: StatelessPlan,
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
    val action = statelessPlanner(operationIds.size, liveValid, oldValid, safetyValid)
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

        StatelessRecoveryAction.PROMOTE_OLD ->
            promoteStatelessSource(context, dir, live, op, names, RollbackSource.OLD, malformedStateExists, faults)

        StatelessRecoveryAction.PROMOTE_SAFETY ->
            promoteStatelessSource(context, dir, live, op, names, RollbackSource.SAFETY, malformedStateExists, faults)

        StatelessRecoveryAction.EMERGENCY -> RestoreRecovery.EMERGENCY
    }
}

/** Promotes the copy the stateless planner named; the planner's action is the only choice between them. */
private fun RestoreTransaction.promoteStatelessSource(
    context: Context,
    dir: File,
    live: File,
    op: String,
    names: RestoreArtifactNames,
    rollbackSource: RollbackSource,
    malformedStateExists: Boolean,
    faults: RestoreFaultInjector,
): RestoreRecovery {
    val source = File(dir, if (rollbackSource == RollbackSource.OLD) names.old else names.safety)
    if (malformedStateExists) archiveCorruptState(dir, op, faults)
    val state = createStatelessRecoveryState(
        op = op,
        names = names,
        source = source,
        rollbackSource = rollbackSource,
    )
    writeState(dir, state, faults)
    return completeStartupRollback(context, dir, live, state, rollbackSource, faults)
}

/**
 * COMPLETE is durable history, not authority over the live database. Once cleanup artifacts
 * are gone it must not impose restore-specific schema checks on later app versions; the normal
 * database startup gate owns version inspection and migration. If completion cleanup was
 * interrupted, resume only that idempotent cleanup without reopening recovery or inspecting
 * the live database.
 */
private fun RestoreTransaction.recoverCompletedHistory(
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

    if (state.outcome == Outcome.INSTALLED) markArtSweepPending(context)
    if (cleanupCompletedRestore(context, dir, state, faults)) clearIncident(dir)
    return RestoreRecovery.NORMAL
}

/**
 * Runs the cleanup that follows a durable COMPLETE journal. The outcome is already validated and
 * recorded, and each start retries whatever cleanup left behind, so a failure here is logged
 * instead of blocking startup. Returns whether the cleanup finished.
 */
private fun RestoreTransaction.cleanupCompletedRestore(
    context: Context,
    dir: File,
    state: State,
    faults: RestoreFaultInjector,
): Boolean = try {
    cleanupAfterColdValidation(context, dir, state, faults)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Log.w(TAG, "Restore cleanup after a COMPLETE journal failed; the next start retries it", failure)
    false
}

private fun RestoreTransaction.createStatelessRecoveryState(
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

private fun RestoreTransaction.completeStartupRollback(
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
