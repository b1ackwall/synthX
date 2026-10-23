package com.v1per.synthx.data.backup

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.v1per.synthx.data.backup.RestoreTransaction.Outcome
import com.v1per.synthx.data.backup.RestoreTransaction.State
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.io.RandomAccessFile

// RestoreTransaction's file primitives: rename, directory sync, artifact validation, cleanup and archiving.

internal fun RestoreTransaction.validateInstalled(live: File, state: State) {
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

internal fun RestoreTransaction.validateOld(file: File, state: State): Boolean {
    if (!validateStandalone(file, currentOnly = true)) return false
    val size = state.oldSize
    val sha = state.oldSha256
    return if (size != null && sha != null) {
        file.length() == size && BackupHashing.hash(file).sha256 == sha
    } else {
        true
    }
}

internal fun RestoreTransaction.validateStandalone(file: File, currentOnly: Boolean): Boolean = runCatching {
    if (!file.isFile) return@runCatching false
    val result = BackupSnapshotValidator.validate(file, migrationSnapshot = !currentOnly)
    !currentOnly || result.schema.userVersion == SynthXDatabase.VERSION
}.getOrDefault(false)

internal fun RestoreTransaction.validateSafety(file: File, state: State): Boolean = file.isFile &&
    file.length() == state.safetySize &&
    BackupHashing.hash(file).sha256 == state.safetySha256 &&
    validateStandalone(file, currentOnly = true)

internal fun RestoreTransaction.force(file: File) = RandomAccessFile(file, "rw").channel.use { it.force(true) }

internal fun RestoreTransaction.rename(
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

internal fun RestoreTransaction.rawRename(from: File, to: File) {
    check(from.exists()) { "Missing ${from.name}" }
    check(!to.exists()) { "Unexpected ${to.name}" }
    try {
        Os.rename(from.path, to.path)
    } catch (error: ErrnoException) {
        throw IllegalStateException("Could not rename ${from.name}", error)
    }
}

internal fun RestoreTransaction.parkLiveIfPresent(
    dir: File,
    live: File,
    preferredName: String,
    faults: RestoreFaultInjector,
) {
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

internal fun RestoreTransaction.quarantineSidecars(live: File, old: File) = detachSidecars(live, old)

internal fun RestoreTransaction.detachSidecars(source: File, target: File) {
    listOf("-wal", "-shm", "-journal").forEach { suffix ->
        val sidecar = File(source.path + suffix)
        if (sidecar.exists()) rawRename(sidecar, File(target.path + suffix))
    }
}

internal fun RestoreTransaction.cleanupPreClose(dir: File, state: State, faults: RestoreFaultInjector) {
    deleteFileSet(File(dir, state.candidateName))
    deleteFileSet(File(dir, state.safetyName))
    File(dir, STATE_FILE).delete()
    File(dir, STATE_TEMP_FILE).delete()
    syncDirectory(dir, RestoreFaultPoint.PRE_CLOSE_CLEANUP_DIRECTORY_SYNC, faults)
}

internal fun RestoreTransaction.cleanupAfterColdValidation(
    context: Context,
    dir: File,
    state: State,
    faults: RestoreFaultInjector,
) {
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

internal fun RestoreTransaction.archiveOperationArtifacts(
    dir: File,
    operationId: String,
    faults: RestoreFaultInjector,
) {
    archiveRecoveredArtifacts(
        dir.listFiles().orEmpty().filter {
            RestoreArtifactNames.parseOperationId(it.name) == operationId
        },
        dir,
        faults,
    )
}

internal fun RestoreTransaction.archiveRecoveredArtifacts(
    artifacts: List<File>,
    dir: File,
    faults: RestoreFaultInjector,
) {
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

internal fun RestoreTransaction.deleteFileSet(file: File) {
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

internal fun RestoreTransaction.archiveCorruptState(dir: File, operationId: String, faults: RestoreFaultInjector) {
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

internal fun RestoreTransaction.syncDirectory(dir: File, faultPoint: RestoreFaultPoint, faults: RestoreFaultInjector) {
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
