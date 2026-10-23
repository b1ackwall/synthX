package com.v1per.synthx.data.backup

import android.content.Context
import com.v1per.synthx.data.backup.RestoreTransaction.Phase
import com.v1per.synthx.data.backup.RestoreTransaction.State
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.io.FileOutputStream

// RestoreTransaction's emergency replacement: the terminal UI's restore path while recovery is locked.

/** Explicit recovery path for the terminal UI. It never opens live Room or normal admission. */
internal suspend fun RestoreTransaction.executeEmergencyReplacement(
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
