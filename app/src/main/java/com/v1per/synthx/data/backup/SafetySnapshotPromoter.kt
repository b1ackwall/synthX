package com.v1per.synthx.data.backup

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File

/**
 * Promotes a completed restore's safety snapshot to an Auto-tier backup, kept apart from
 * [RestoreTransaction].
 *
 * It is the one part of that file with no stake in the crash-resumable phase machine: it is reached
 * only from cleanupAfterColdValidation, strictly after Phase.COMPLETE is already durable, it reads
 * nothing beyond the [RestoreTransaction.State] handed to it, and failures are retryable from that
 * completed phase (see [promoteSafetyToAutoTier]). Everything else in RestoreTransaction.kt stays
 * put — that coupling is what makes its safety guarantees provable.
 *
 * Following [MigrationRecoveryAction], this object keeps its own private copies of the three
 * filesystem primitives it needs rather than widening RestoreTransaction's private members to
 * internal — see the note at the top of RestoreTransaction.kt on not weakening encapsulation on
 * safety-critical code.
 */
internal object SafetySnapshotPromoter {
    // Deliberately still "RestoreTransaction": moving this code must not move its logcat output.
    private const val TAG = "RestoreTransaction"

    /**
     * Retryable republish of the pre-restore safety snapshot as an ordinary Auto-tier
     * backup. The restore itself is already complete, but a local filesystem failure aborts
     * this cold-start cleanup so its owned staging evidence survives for the next attempt. The rename to
     * the reserved `safetyPromotion` name happens first so a crash between rename and publish
     * still leaves behind a file [RestoreArtifactNames.parseOperationId] recognizes as owned
     * by this operation (rather than an unrecognized stray triggering emergency recovery), and
     * so a retried cleanup pass (idempotent, like the rest of this recovery path) can resume
     * from either the original or the already-renamed location.
     */
    fun promoteSafetyToAutoTier(
        context: Context,
        dir: File,
        state: RestoreTransaction.State,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    ) {
        val names = RestoreArtifactNames(state.operationId)
        val safety = File(dir, state.safetyName)
        val staged = File(dir, names.safetyPromotion)
        try {
            if (safety.isFile) {
                if (staged.exists()) deleteFileSet(staged)
                rawRename(safety, staged)
                syncDirectory(
                    dir,
                    RestoreFaultPoint.SAFETY_PROMOTION_STAGE_DIRECTORY_SYNC,
                    faults,
                )
            }
            if (staged.isFile) {
                val validated = validateHistoricalSafetySnapshot(staged, state)
                publishSafetyPromotion(context, staged, validated)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not promote pre-restore safety snapshot to Auto tier", error)
            throw error
        }
        deleteFileSet(staged)
        deleteFileSet(safety)
        syncDirectory(
            dir,
            RestoreFaultPoint.SAFETY_PROMOTION_CLEANUP_DIRECTORY_SYNC,
            faults,
        )
    }

    private fun validateHistoricalSafetySnapshot(
        staged: File,
        state: RestoreTransaction.State,
    ): ValidatedBackupSnapshot {
        check(staged.length() == state.safetySize) {
            "Completed restore safety snapshot size does not match journal"
        }
        check(BackupHashing.hash(staged).sha256 == state.safetySha256) {
            "Completed restore safety snapshot hash does not match journal"
        }
        return BackupSnapshotValidator.validate(staged, migrationSnapshot = true)
    }

    /**
     * Routed through [BackupManager.publishExistingSnapshot] instead of calling
     * [SafBackupPublisher] directly — this is what gives promotion normal Auto-tier retention
     * and crash-safe idempotence (a retry after a crash between publish and local cleanup
     * re-hashes the same staged bytes and finds its own prior publish instead of generating a
     * fresh name and republishing — see that method's doc).
     *
     * "No backup folder configured" is preserved as a deliberate, silent skip (not an error): a
     * device with backups turned off entirely is a valid steady state and must not fail restore's
     * cold-start cleanup. Everything else — including a folder that's configured but currently
     * unreachable — surfaces as a genuine, logged, retryable failure via the exception this
     * throws, rather than being silently and permanently dropped.
     */
    private fun publishSafetyPromotion(
        context: Context,
        staged: File,
        validated: ValidatedBackupSnapshot,
    ) {
        if (PreferencesManager(context).backupFolderUri == null) return
        BackupManager.create(context).publishExistingSnapshot(
            tier = BackupTier.Auto,
            reason = BackupReason.PRE_RESTORE_SAFETY,
            snapshot = staged,
            dbVersion = validated.schema.userVersion,
        ).exceptionOrNull()?.let { throw it }
    }

    // Verbatim copies of RestoreTransaction's file primitives, kept private here for the same
    // reason MigrationRecoveryAction keeps its own: neither is worth widening that object's
    // encapsulation for. Both are pure functions of their arguments with no shared state.

    private fun rawRename(from: File, to: File) {
        check(from.exists()) { "Missing ${from.name}" }
        check(!to.exists()) { "Unexpected ${to.name}" }
        try {
            Os.rename(from.path, to.path)
        } catch (error: ErrnoException) {
            throw IllegalStateException("Could not rename ${from.name}", error)
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

    private fun syncDirectory(
        dir: File,
        faultPoint: RestoreFaultPoint,
        faults: RestoreFaultInjector,
    ) {
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
