package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File

/**
 * The P4.4 promotion of a completed restore's safety snapshot to an Auto-tier backup, moved here
 * verbatim from [RestoreTransaction].
 *
 * It is the one part of that file with no stake in the crash-resumable phase machine: it is reached
 * only from cleanupAfterColdValidation, strictly after Phase.COMPLETE is already durable, it reads
 * nothing beyond the [RestoreTransaction.State] handed to it, and it is best-effort by construction
 * (see [promoteSafetyToAutoTier]). Everything else in RestoreTransaction.kt stays put — that
 * coupling is what makes its safety guarantees provable.
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
     * Best-effort republish of the pre-restore safety snapshot as an ordinary Auto-tier
     * backup. Never allowed to fail the surrounding cold-start cleanup — the restore itself
     * already succeeded regardless of whether this bonus backup gets created. The rename to
     * the reserved `safetyPromotion` name happens first so a crash between rename and publish
     * still leaves behind a file [RestoreArtifactNames.parseOperationId] recognizes as owned
     * by this operation (rather than an unrecognized stray triggering emergency recovery), and
     * so a retried cleanup pass (idempotent, like the rest of this recovery path) can resume
     * from either the original or the already-renamed location.
     */
    fun promoteSafetyToAutoTier(context: Context, dir: File, state: RestoreTransaction.State) {
        val names = RestoreArtifactNames(state.operationId)
        val safety = File(dir, state.safetyName)
        val staged = File(dir, names.safetyPromotion)
        try {
            if (safety.isFile) {
                if (staged.exists()) deleteFileSet(staged)
                rawRename(safety, staged)
                syncDirectory(dir)
            }
            if (staged.isFile) publishSafetyPromotion(context, staged)
        } catch (error: Exception) {
            Log.w(TAG, "Could not promote pre-restore safety snapshot to Auto tier", error)
        } finally {
            deleteFileSet(staged)
            deleteFileSet(safety)
            syncDirectory(dir)
        }
    }

    private fun publishSafetyPromotion(context: Context, staged: File) {
        val treeUri = PreferencesManager(context).backupFolderUri?.let(Uri::parse) ?: return
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        val tierDirectory = root.findFile(BackupTier.Auto.folderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(BackupTier.Auto.folderName)
            ?: return

        val validated = BackupSnapshotValidator.validate(staged, migrationSnapshot = false)
        val digest = BackupHashing.hash(staged)
        val identity = BackupFileNames.create()
        val metadata = BackupMetadata(
            timestamp = System.currentTimeMillis(),
            tier = BackupTier.Auto.name,
            reason = BackupReason.PRE_RESTORE_SAFETY,
            dbVersion = validated.schema.userVersion,
            file = identity.databaseFileName,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )
        SafBackupPublisher(context).publish(
            tierDirectory = tierDirectory,
            identity = identity,
            snapshot = staged,
            digest = digest,
            metadataBytes = BackupMetadataCodec.encode(metadata),
        )
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
