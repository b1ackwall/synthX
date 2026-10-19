package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import com.v1per.synthx.R
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Whole-database backup and restore against a user-chosen SAF tree.
 *
 * Ordinary backups snapshot through Room's live SupportSQLiteDatabase. The migration reason is
 * deliberately special: it snapshots through a raw framework handle so opening Room cannot migrate
 * the source before its pre-migration state is captured.
 */
open class BackupManager(internal val context: Context) {

    private val preferences = PreferencesManager(context)

    // ---------------------------------------------------------------- create

    /**
     * @param cancelActiveScan pass `false` from inside [LibraryScanWorker]. The worker is itself the
     *   running work, so waiting for the unique work to reach a terminal state from within it would
     *   never return.
     * @return on success, the database filename (e.g. "synthx_backup_...db") of the backup just
     *   created — [BackupFileIdentity] itself stays internal, so only the filename crosses out.
     */
    open suspend fun createBackup(
        tier: BackupTier,
        reason: String? = null,
        cancelActiveScan: Boolean = true,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            RestoreStartup.await()
            // Let every already-admitted finite operation land before the snapshot — most
            // importantly QueuePersistence's cursor/queue writes, which are fire-and-forget:
            // the caller's RestoreCoordinator permit is acquired synchronously but the actual
            // Room write only happens later on QueuePersistence's own IO writer. Without this,
            // VACUUM INTO below could snapshot the live database before an already-admitted
            // cursor update actually lands, silently backing up a stale playback position.
            // Must run BEFORE this call's own withDbOperation acquires ITS permit — awaiting
            // drain from inside that permit would wait on itself and never complete, since the
            // permit doesn't release until this whole block finishes.
            RestoreCoordinator.awaitFiniteDrained()
            RestoreCoordinator.withDbOperation {
                BackupCreationCoordinator.serialize {
                    createBackupLocked(tier, reason, cancelActiveScan)
                }
            }
        }.also { result ->
            // Every tier/reason funnels through this one function, so recording the
            // outcome here — success or failure — is what makes it true for all of them at
            // once, rather than something each of the six call sites has to remember to do.
            BackupOutcomeStore.recordBackupOutcome(context, tier, reason, result)
        }
    }

    /**
     * [DatabaseStartupGate]'s only production pre-migration entry point. It runs the complete
     * createBackup pipeline, but cannot wait for/cancel the Worker that may itself be the process's first gate caller.
     * [BackupReason.MIGRATION] selects [BackupSnapshotter.createForMigration], so this path never
     * obtains Room while the gate is still closed.
     */
    internal suspend fun createPreMigrationBackup(): Result<String> = createBackup(
        tier = BackupTier.Auto,
        reason = BackupReason.MIGRATION,
        cancelActiveScan = false,
    )

    private suspend fun createBackupLocked(
        tier: BackupTier,
        reason: String?,
        cancelActiveScan: Boolean,
    ): String {
        val treeUri = preferences.backupFolderUri?.let { it.toUri() }
            ?: throw BackupNotConfiguredException()
        val sourceFile = context.getDatabasePath(SynthXDatabase.DB_NAME)
        if (!sourceFile.isFile) error("No database to back up yet")

        if (cancelActiveScan) awaitScanIdle()

        val tierDirectory = resolveTierDir(treeUri, tier)
            ?: error("Backup folder is unreachable — re-select it in Backups and Restore")
        val stage = BackupStagingCoordinator.acquire(context, "snapshot")
        val snapshot = stage.file

        return try {
            val migrationSnapshot = reason == BackupReason.MIGRATION
            if (migrationSnapshot) {
                BackupSnapshotter.createForMigration(sourceFile, snapshot)
            } else {
                val roomDatabase = SynthXDatabase.getInstance(context)
                BackupSnapshotter.createFromRoom(
                    roomDatabase.openHelper.writableDatabase,
                    snapshot,
                )
            }

            BackupSnapshotter.forceToStorage(snapshot)
            val validated = BackupSnapshotValidator.validate(snapshot, migrationSnapshot)
            val digest = BackupHashing.hash(snapshot)
            if (digest.sizeBytes <= 0L || digest.sizeBytes != snapshot.length()) {
                throw BackupSnapshotException("Local snapshot size changed while hashing")
            }

            val timestamp = System.currentTimeMillis()
            val identity = BackupFileNames.create(Instant.ofEpochMilli(timestamp))
            val metadata = BackupMetadata(
                timestamp = timestamp,
                tier = tier.name,
                reason = reason,
                dbVersion = validated.schema.userVersion,
                file = identity.databaseFileName,
                sizeBytes = digest.sizeBytes,
                sha256 = digest.sha256,
            )

            publishAndRetain(tierDirectory, tier, identity, snapshot, digest, metadata)
        } finally {
            stage.close()
        }
    }

    /**
     * Shared tail of [createBackupLocked] and [publishExistingSnapshot]: publish, then run
     * retention against the tier directory. Publication is complete once [SafBackupPublisher]
     * returns — retention failures never turn a valid, published backup into a reported failure
     * (see [enforceRetention]'s own comment), so they are caught and logged here, not propagated.
     */
    private fun publishAndRetain(
        tierDirectory: DocumentFile,
        tier: BackupTier,
        identity: BackupFileIdentity,
        snapshot: File,
        digest: ByteDigest,
        metadata: BackupMetadata,
    ): String {
        SafBackupPublisher(context).publish(
            tierDirectory = tierDirectory,
            identity = identity,
            snapshot = snapshot,
            digest = digest,
            metadataBytes = BackupMetadataCodec.encode(metadata),
        )
        runCatching { enforceRetention(tierDirectory, tier, identity) }
            .onFailure { Log.w(TAG, "Backup retention skipped", it) }
        return identity.databaseFileName
    }

    /**
     * Publishes an ALREADY-MATERIALIZED snapshot file (no fresh [BackupSnapshotter]
     * capture) through the same identity → [SafBackupPublisher] → [enforceRetention] →
     * [BackupOutcomeStore] pipeline [createBackup] uses for a freshly-captured one. Exists for
     * [SafetySnapshotPromoter], which has a historical, already-hashed/validated file on disk and
     * needs it published as an ordinary tier backup, not re-snapshotted.
     *
     * Deliberately does NOT go through [RestoreCoordinator.withDbOperation]/
     * [BackupCreationCoordinator] the way [createBackup] does: those gates quiesce ORDINARY app DB
     * traffic during a restore, but this function IS restore's own internal machinery (see the
     * CLAUDE.md architecture note on legitimate bypasses), called synchronously from inside
     * [RestoreTransaction]'s cold-start recovery — gating on itself here would either deadlock or
     * wait on state this very call is responsible for finishing. It is also, correspondingly, not
     * a suspend function, matching the synchronous call site.
     *
     * Idempotent by content, not by filename: before publishing, this checks whether a completed
     * backup with the same [tier]/[reason]/SHA-256 already exists and, if so, returns its name
     * without republishing. A promotion retried after a crash between a successful publish and the
     * caller's own local cleanup re-hashes the same staged bytes, finds its own prior publish, and
     * skips straight to the (idempotent, harmless-to-repeat) local cleanup instead of generating a
     * fresh [BackupFileNames.create] identity and publishing a second copy under a new name.
     */
    internal fun publishExistingSnapshot(
        tier: BackupTier,
        reason: String,
        snapshot: File,
        dbVersion: Int,
    ): Result<String> = runCatching {
        val treeUri = preferences.backupFolderUri?.let { it.toUri() }
            ?: throw BackupNotConfiguredException()
        val tierDirectory = resolveTierDir(treeUri, tier)
            ?: error("Backup folder is unreachable — re-select it in Backups and Restore")
        publishExistingSnapshotTo(tierDirectory, tier, reason, snapshot, dbVersion)
    }.also { result ->
        BackupOutcomeStore.recordBackupOutcome(context, tier, reason, result)
    }

    /**
     * Split out from [publishExistingSnapshot] so tests can supply a real, file-backed
     * [DocumentFile] tier directory (as [classifyBackupList] already does for listing) instead of
     * needing a genuine SAF tree grant, which [resolveTierDir] requires.
     */
    internal fun publishExistingSnapshotTo(
        tierDirectory: DocumentFile,
        tier: BackupTier,
        reason: String,
        snapshot: File,
        dbVersion: Int,
    ): String {
        val digest = BackupHashing.hash(snapshot)
        if (digest.sizeBytes <= 0L) throw BackupSnapshotException("Snapshot to publish is empty")

        val children = runCatching { tierDirectory.listFiles().toList() }.getOrDefault(emptyList())
        val existing = completedPairs(children, tier).firstOrNull {
            it.metadata.reason == reason && it.metadata.sha256 == digest.sha256
        }
        val databaseFileName = if (existing != null) {
            // Already published by a prior attempt at this same promotion — re-running retention
            // is cheap and keeps this path's tier-limit behavior identical to a fresh publish's.
            runCatching {
                enforceRetention(tierDirectory, tier, BackupFileIdentity(existing.databaseName.removeSuffix(".db")))
            }.onFailure { Log.w(TAG, "Backup retention skipped", it) }
            existing.databaseName
        } else {
            val timestamp = System.currentTimeMillis()
            val identity = BackupFileNames.create(Instant.ofEpochMilli(timestamp))
            val metadata = BackupMetadata(
                timestamp = timestamp,
                tier = tier.name,
                reason = reason,
                dbVersion = dbVersion,
                file = identity.databaseFileName,
                sizeBytes = digest.sizeBytes,
                sha256 = digest.sha256,
            )
            publishAndRetain(tierDirectory, tier, identity, snapshot, digest, metadata)
        }
        return databaseFileName
    }

    // --------------------------------------------------------------- listing

    /**
     * All backups across every tier, newest first, distinguishing why the list might be empty.
     * `open`: a test seam so [BackupsAndRestoreViewModel] tests can substitute a fixed
     * [BackupListResult] without a genuine SAF tree grant — the same reason [ensureFolderStructure]
     * is `open` below.
     */
    open suspend fun listBackups(): BackupListResult = withContext(Dispatchers.IO) {
        val treeUri = preferences.backupFolderUri?.let { it.toUri() }
            ?: return@withContext BackupListResult.NoFolderConfigured
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        classifyBackupList(root)
    }

    // ------------------------------------------------------------ verification

    /**
     * Re-validates the most recent restore-*eligible* backup's actual bytes against the same
     * ground truth [RestoreTransaction.execute] itself checks: a fresh SHA-256/size compare
     * against metadata's declared values, not just structural validation — catching
     * corruption a healthy write wouldn't have (bit rot, a provider that silently truncated the
     * file after publish, a SAF provider that edited a syntactically-valid file in place, etc.).
     * Only [BackupInfo.restoreEligible] entries are candidates: the eligibility check is
     * cheap/already computed during listing and correctly excludes duplicate-named, missing-
     * metadata, or size-mismatched entries before this expensive byte-level pass ever runs.
     * `migrationSnapshot = true` matches restore's own first-pass validation (in
     * RestoreTransaction.execute) so a genuinely supported older-schema backup is not rejected here just because
     * it isn't already at the current schema version.
     *
     * No eligible backup to check (none published yet, or every listed entry is ineligible) is
     * recorded as a distinct "nothing to verify" outcome, never as a false verification success —
     * see [BackupOutcomeStore.recordVerificationSkipped].
     *
     * Called from [com.v1per.synthx.MainActivity]'s existing once-per-day gate rather than a
     * second, uncoordinated timer — see `backupDailyIfDue`.
     */
    suspend fun verifyMostRecentBackup(): Result<Unit> =
        verifyBackupList(runCatching { listBackups() }.getOrDefault(BackupListResult.FolderUnreachable))

    // ---------------------------------------------------------------- preview

    /**
     * Read-only playlist/song/playlist-membership counts for [backup], obtained by staging the
     * candidate locally (same [RestoreTransaction.copySafOnce] staging [verifyMostRecentBackup]
     * already uses) and querying the staged copy directly — entirely independent of
     * [RestoreTransaction]/[RestoreCoordinator]: no live-DB quiescing or admission gating, since
     * nothing here touches the live database or commits to a restore.
     */
    suspend fun previewBackup(
        backup: BackupInfo,
        onProgress: (RestoreProgressStep) -> Unit = {},
    ): Result<BackupPreviewCounts> = previewBackupInternal(backup, onProgress)

    // --------------------------------------------------------------- restore

    /**
     * Overwrites the live database with [backup]. The caller must terminate the process
     * immediately afterwards — see [SynthXDatabase.closeInstance].
     */
    suspend fun restore(
        backup: BackupInfo,
        requestId: String,
        onProgress: suspend (RestoreProgressStep) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        RestoreStartup.await()
        // A pending restore may itself be the first Worker in a new app process. Its safety
        // snapshot opens live Room, so it must cross DatabaseStartupGate just like every other
        // live Room owner.
        DatabaseStartupGate.awaitReady()
        val metadata = readRestoreMetadata(backup)
            ?: return@withContext Result.failure(
                IllegalArgumentException(context.getString(R.string.restore_ineligible_missing_metadata)),
            )
        RestoreTransaction.execute(context, backup, metadata, requestId, onProgress = onProgress)
    }

    /** Terminal-only SAF recovery. Deliberately bypasses normal startup/Room gates. */
    suspend fun emergencyRestore(backup: BackupInfo, requestId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val metadata = readRestoreMetadata(backup)
                ?: return@withContext Result.failure(
                    IllegalArgumentException(context.getString(R.string.restore_ineligible_missing_metadata)),
                )
            RestoreTransaction.executeEmergencyReplacement(context, backup, metadata, requestId)
        }

    /** Backup metadata is mandatory for a trustworthy restore, though old files may still list. */
    internal suspend fun readRestoreMetadata(backup: BackupInfo): BackupMetadata? = withContext(Dispatchers.IO) {
        val metadata = backup.metadataUri?.let(::readMetadata) ?: return@withContext null
        return@withContext runCatching {
            metadata.requireCompleteForPublication()
            require(metadata.file == backup.fileName)
            metadata
        }.getOrNull()
    }

    // ----------------------------------------------------------------- setup

    /**
     * Creates Daily/Auto/Manual under [treeUri] if absent. Safe to call repeatedly. `open`: test
     * seam, see [listBackups].
     */
    open fun ensureFolderStructure(treeUri: Uri): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        return BackupTier.entries.all { tier ->
            val existing = root.findFile(tier.folderName)
            existing?.isDirectory == true || root.createDirectory(tier.folderName) != null
        }
    }

    // --------------------------------------------------------------- helpers

    private suspend fun awaitScanIdle() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME)
        workManager.getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
            .first { infos -> infos.all { it.state.isFinished } }
    }

    @VisibleForTesting
    internal open fun resolveTierDir(
        treeUri: Uri,
        tier: BackupTier,
        create: Boolean = true,
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val existing = root.findFile(tier.folderName)
        if (existing != null && existing.isDirectory) return existing
        return if (create) root.createDirectory(tier.folderName) else null
    }

    internal fun readMetadata(uri: Uri): BackupMetadata? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BackupMetadataCodec.decodeOrNull(input.readBytes())
        }
    }.getOrNull()

    companion object {
        private const val TAG = "BackupManager"

        /** Manual-DI entry point, matching SongRepositoryImpl.create. */
        fun create(context: Context): BackupManager = BackupManager(context.applicationContext)
    }
}
