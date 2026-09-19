package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import com.v1per.synthx.R
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Backup tiers, each retained independently in its own subdirectory. */
enum class BackupTier(val folderName: String, val retention: Int) {
    Daily("Daily", 20),
    Auto("Auto", 30),
    Manual("Manual", 20),
}

/** Reason strings recorded in the sidecar for [BackupTier.Auto] snapshots. */
object BackupReason {
    const val MIGRATION = "migration"
    const val FOLDER_CHANGE = "folder_change"
    const val DELETE_SONG = "delete_song"
    const val DELETE_PLAYLIST = "delete_playlist"
    const val REPLACE_SONG = "replace_song"
    const val RESCAN = "rescan"
    const val PRE_RESTORE_SAFETY = "pre_restore_safety"
}

/** Read-only content counts for a candidate backup (P4.3 restore preview), never a live query. */
data class BackupPreviewCounts(
    val playlistCount: Int,
    val songCount: Int,
    val playlistEntryCount: Int,
)

data class BackupInfo(
    val databaseUri: Uri,
    val metadataUri: Uri?,
    val fileName: String,
    val tier: BackupTier,
    val timestamp: Long,
    val reason: String?,
    val sizeBytes: Long,
    val restoreEligible: Boolean = false,
    val restoreIneligibleReason: BackupIneligibilityReason? = null,
)

/**
 * Why a listed backup can't be restored — distinguishing causes that [BackupManager.listBackups]
 * used to collapse into one "Legacy backup" literal (R7-F07). Derived from data already read
 * while listing (metadata presence/contents, actual file size, display-name collisions, schema
 * contract lookup) rather than a value discarded elsewhere — nothing upstream already computes
 * this breakdown. [Unrecognized] is the residual bucket: [BackupManager]'s authoritative eligibility
 * check (a fully validated [CompletedBackup] pair with a supported schema) failed for a reason
 * none of the other cases identify, e.g. a structurally invalid metadata field.
 */
sealed interface BackupIneligibilityReason {
    data object MissingMetadata : BackupIneligibilityReason
    data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : BackupIneligibilityReason
    data object DuplicateDisplayName : BackupIneligibilityReason
    data class UnsupportedSchema(val dbVersion: Int) : BackupIneligibilityReason
    data object Unrecognized : BackupIneligibilityReason
}

/**
 * Derives [BackupIneligibilityReason] from facts already on hand while listing a candidate —
 * pure and Android-free so it is unit-testable without a real SAF tree. Checked in this order:
 * a duplicate display name makes the entry ambiguous regardless of what its metadata claims;
 * missing metadata is worse than a stale one; a size mismatch is checked before schema support
 * since a corrupt/truncated file's schema claim can't be trusted either. Returns null when none
 * of these checks find fault — callers combine that with their own authoritative eligibility
 * check, since this function alone cannot see every validation [CompletedBackupCatalog] applies.
 */
internal fun classifyIneligibility(
    metadata: BackupMetadata?,
    actualSizeBytes: Long,
    isDuplicateName: Boolean,
    schemaSupported: Boolean,
): BackupIneligibilityReason? = when {
    isDuplicateName -> BackupIneligibilityReason.DuplicateDisplayName
    metadata == null -> BackupIneligibilityReason.MissingMetadata
    metadata.sizeBytes != null && metadata.sizeBytes != actualSizeBytes ->
        BackupIneligibilityReason.SizeMismatch(metadata.sizeBytes, actualSizeBytes)
    !schemaSupported -> BackupIneligibilityReason.UnsupportedSchema(metadata.dbVersion)
    else -> null
}

/**
 * The outcome of listing candidate backups (R7-F04) — distinguishes situations
 * [BackupManager.listBackups] used to collapse into one identical empty list: no folder chosen
 * yet, a folder that can't currently be read (missing/renamed tier directories or a provider
 * listing failure — merged into one case because both mean the same thing to the user: "we can't
 * see what's in the folder you picked," with the same remedy), a folder that reads fine but
 * genuinely holds nothing, and an actual list.
 */
sealed interface BackupListResult {
    data object NoFolderConfigured : BackupListResult
    data object FolderUnreachable : BackupListResult
    data object Empty : BackupListResult
    data class Success(val backups: List<BackupInfo>) : BackupListResult
}

/** For callers that only want "whatever's there, or nothing" without caring why. */
internal fun BackupListResult.backupsOrEmpty(): List<BackupInfo> =
    (this as? BackupListResult.Success)?.backups.orEmpty()

class BackupNotConfiguredException : IllegalStateException("No backup folder selected")

/**
 * Whole-database backup and restore against a user-chosen SAF tree.
 *
 * Ordinary backups snapshot through Room's live SupportSQLiteDatabase. The migration reason is
 * deliberately special: it snapshots through a raw framework handle so opening Room cannot migrate
 * the source before its pre-migration state is captured.
 */
open class BackupManager(private val context: Context) {

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
            // N13: every tier/reason funnels through this one function, so recording the
            // outcome here — success or failure — is what makes it true for all of them at
            // once, rather than something each of the six call sites has to remember to do.
            BackupOutcomeStore.recordBackupOutcome(context, tier, reason, result)
        }
    }

    /**
     * C1's only production pre-migration entry point. It retains the complete Phase-A pipeline,
     * but cannot wait for/cancel the Worker that may itself be the process's first gate caller.
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
        val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) }
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
     * R8-A13/P7-T6: publishes an ALREADY-MATERIALIZED snapshot file (no fresh [BackupSnapshotter]
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
        val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) }
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
        val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) }
            ?: return@withContext BackupListResult.NoFolderConfigured
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        classifyBackupList(root)
    }

    /**
     * Split out from [listBackups] so tests can supply a real, file-backed [DocumentFile] root
     * (as [SafBackupPublisherInstrumentedTest] already does via `DocumentFile.fromFile`) instead
     * of needing a genuine SAF tree grant, which `DocumentFile.fromTreeUri` requires.
     */
    internal fun classifyBackupList(root: DocumentFile?): BackupListResult {
        // A revoked grant or a provider that has gone away can make even `.isDirectory` throw,
        // not just return false — this is the same distrust-the-provider posture as the
        // `runCatching` around `listFiles()` below.
        val safeRoot = root?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }
            ?: return BackupListResult.FolderUnreachable

        var anyTierUnreadable = false
        val backups = BackupTier.entries.flatMap { tier ->
            val directory = runCatching { safeRoot.findFile(tier.folderName) }.getOrNull()
                ?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }
            if (directory == null) {
                anyTierUnreadable = true
                return@flatMap emptyList()
            }
            val children = runCatching { directory.listFiles().toList() }
                .onFailure { anyTierUnreadable = true }
                .getOrDefault(emptyList())
            val nameCounts = children.groupingBy { it.name.orEmpty() }.eachCount()
            val byName = children.associateBy { it.name.orEmpty() }
            val completedByDatabaseName = completedPairs(children, tier)
                .associateBy { it.databaseName }

            children
                .filter { document ->
                    document.isFile &&
                        document.name?.let(BackupFileNames::isCompletedDatabaseName) == true
                }
                .map { databaseDocument ->
                    val name = databaseDocument.name.orEmpty()
                    val stem = name.removeSuffix(".db")
                    val completePair = completedByDatabaseName[name]
                    val metadataDocument = completePair?.metadataDocument?.document
                        ?: byName["$stem.json"]
                    val metadata = completePair?.metadata
                        ?: metadataDocument?.let { readMetadata(it.uri) }
                    val schemaSupported = metadata?.let { value ->
                        runCatching {
                            value.dbVersion <= SynthXDatabase.VERSION &&
                                BackupSchemaContracts.forVersion(value.dbVersion)?.hasMigrationPathToCurrent == true
                        }.getOrDefault(false)
                    } == true
                    val eligible = completePair != null && schemaSupported
                    val ineligibleReason = if (eligible) {
                        null
                    } else {
                        classifyIneligibility(
                            metadata = metadata,
                            actualSizeBytes = databaseDocument.length(),
                            isDuplicateName = (nameCounts[name] ?: 0) > 1,
                            schemaSupported = schemaSupported,
                        ) ?: BackupIneligibilityReason.Unrecognized
                    }
                    BackupInfo(
                        databaseUri = databaseDocument.uri,
                        metadataUri = metadataDocument?.uri,
                        fileName = name,
                        tier = tier,
                        timestamp = metadata?.timestamp?.takeIf { it > 0 }
                            ?: BackupFileNames.parseTimestampMillis(name)
                            ?: databaseDocument.lastModified(),
                        reason = metadata?.reason?.takeIf { it.isNotBlank() },
                        sizeBytes = databaseDocument.length(),
                        restoreEligible = eligible,
                        restoreIneligibleReason = ineligibleReason,
                    )
                }
        }.sortedByDescending { it.timestamp }

        return when {
            backups.isNotEmpty() -> BackupListResult.Success(backups)
            anyTierUnreadable -> BackupListResult.FolderUnreachable
            else -> BackupListResult.Empty
        }
    }

    // ------------------------------------------------------------ verification (P3.2)

    /**
     * Re-validates the most recent restore-*eligible* backup's actual bytes against the same
     * ground truth [RestoreTransaction.execute] itself checks: a fresh SHA-256/size compare
     * against metadata's declared values (R8-A11), not just structural validation — catching
     * corruption a healthy write wouldn't have (bit rot, a provider that silently truncated the
     * file after publish, a SAF provider that edited a syntactically-valid file in place, etc.).
     * Only [BackupInfo.restoreEligible] entries are candidates: P7-T4's eligibility check is
     * cheap/already computed during listing and correctly excludes duplicate-named, missing-
     * metadata, or size-mismatched entries before this expensive byte-level pass ever runs.
     * `migrationSnapshot = true` matches restore's own first-pass validation (RestoreTransaction
     * line ~173) so a genuinely supported older-schema backup is not rejected here just because
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

    /**
     * Split out from [verifyMostRecentBackup] so tests can supply a [BackupListResult] directly
     * — real [BackupInfo] entries built with `file://` URIs and a genuine SQLite backup file —
     * the same technique [classifyBackupList] uses to avoid needing a genuine SAF tree grant.
     */
    internal suspend fun verifyBackupList(listing: BackupListResult): Result<Unit> = withContext(Dispatchers.IO) {
        val eligible = listing.backupsOrEmpty().firstOrNull { it.restoreEligible }

        if (eligible == null) {
            BackupOutcomeStore.recordVerificationSkipped(context)
            return@withContext Result.success(Unit)
        }

        val result = runCatching {
            val metadata = requireNotNull(readRestoreMetadata(eligible)) {
                "Eligible backup is missing valid metadata"
            }
            val stage = BackupStagingCoordinator.acquire(context, "verify")
            val copy = stage.file
            try {
                val digest = RestoreTransaction.copySafOnce(context, eligible.databaseUri.toString(), copy)
                require(digest.sizeBytes == metadata.sizeBytes && digest.sha256 == metadata.sha256) {
                    "Backup bytes do not match its declared size and SHA-256 metadata"
                }
                val validated = BackupSnapshotValidator.validate(copy, migrationSnapshot = true)
                require(validated.schema.userVersion == metadata.dbVersion) {
                    "Backup metadata version does not match the database"
                }
            } finally {
                stage.close()
            }
            Unit
        }
        BackupOutcomeStore.recordVerificationOutcome(context, result)
        result
    }

    // ---------------------------------------------------------------- preview (P4.3)

    /**
     * Read-only playlist/song/playlist-membership counts for [backup], obtained by staging the
     * candidate locally (same [RestoreTransaction.copySafOnce] staging [verifyMostRecentBackup]
     * already uses) and querying the staged copy directly — entirely independent of
     * [RestoreTransaction]/[RestoreCoordinator]: no live-DB quiescing or admission gating, since
     * nothing here touches the live database or commits to a restore.
     */
    suspend fun previewBackup(backup: BackupInfo): Result<BackupPreviewCounts> = withContext(Dispatchers.IO) {
        runCatching {
            val stage = BackupStagingCoordinator.acquire(context, "preview")
            val copy = stage.file
            try {
                RestoreTransaction.copySafOnce(context, backup.databaseUri.toString(), copy)
                // migrationSnapshot=true: a preview must work on a candidate at any restorable
                // schema version, not only one already at the current version.
                BackupSnapshotValidator.validate(copy, migrationSnapshot = true)
                SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    BackupPreviewCounts(
                        playlistCount = countRows(db, "playlists"),
                        songCount = countRows(db, "songs"),
                        playlistEntryCount = countRows(db, "playlist_songs"),
                    )
                }
            } finally {
                stage.close()
            }
        }
    }

    private fun countRows(database: SQLiteDatabase, table: String): Int =
        database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    // --------------------------------------------------------------- restore

    /**
     * Overwrites the live database with [backup]. The caller must terminate the process
     * immediately afterwards — see [SynthXDatabase.closeInstance].
     */
    suspend fun restore(backup: BackupInfo, requestId: String): Result<Unit> = withContext(Dispatchers.IO) {
        RestoreStartup.await()
        // A pending restore may itself be the first Worker in a new app process. Its safety
        // snapshot opens live Room, so it must cross C1 just like every other live Room owner.
        DatabaseStartupGate.awaitReady()
        val metadata = readRestoreMetadata(backup)
            ?: return@withContext Result.failure(
                IllegalArgumentException(context.getString(R.string.restore_ineligible_missing_metadata)),
            )
        RestoreTransaction.execute(context, backup, metadata, requestId)
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

    /** Phase-A metadata is mandatory for a trustworthy restore, though old files may still list. */
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

    private fun resolveTierDir(
        treeUri: Uri,
        tier: BackupTier,
        create: Boolean = true,
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val existing = root.findFile(tier.folderName)
        if (existing != null && existing.isDirectory) return existing
        return if (create) root.createDirectory(tier.folderName) else null
    }

    /**
     * `internal`: a test seam so retention/orphan-sweep behavior can be exercised against a real,
     * file-backed [DocumentFile] tier directory (see [classifyBackupList]'s doc for why a genuine
     * SAF tree grant is unavailable in an instrumented test).
     */
    internal fun enforceRetention(
        tierDirectory: DocumentFile,
        tier: BackupTier,
        protected: BackupFileIdentity,
    ) {
        val children = runCatching { tierDirectory.listFiles().toList() }.getOrDefault(emptyList())
        val outcome = BackupRetention.prune(
            completed = completedPairs(children, tier),
            limit = tier.retention,
            protectedDatabaseName = protected.databaseFileName,
        ) { pair ->
            // Attempt both sides independently and report what actually happened — a thrown
            // exception or a refused (Boolean false) SAF delete are both now visible to the
            // caller instead of being discarded, per R8-A12.
            val databaseDeleted = runCatching { pair.database.document.delete() }.getOrDefault(false)
            val metadataDeleted = runCatching { pair.metadataDocument.document.delete() }.getOrDefault(false)
            PairDeletionResult(databaseDeleted, metadataDeleted)
        }
        BackupOutcomeStore.recordRetentionOutcome(context, tier, outcome)

        // R8-A12: fold orphan cleanup into this same directory listing rather than a separate
        // scan — reuses the DocumentFile handles already fetched above; deletions above are
        // reflected live since DocumentFile.exists()/isFile query the provider, not a cache.
        sweepOrphanHalves(children)
    }

    /**
     * R8-A12: removes a stray database or metadata file that has no matching other half — either
     * left behind by a partial pruning deletion above, or from an earlier run that crashed
     * mid-publish/mid-prune. A stray database half is unrestorable through the supported UI (no
     * metadata pointing at it); a stray metadata half references bytes that no longer exist.
     * Neither can be "repaired" by re-pairing against a DIFFERENT orphan — pairing is purely
     * name-derived (see [CompletedBackupCatalog]), so if two files' stems matched they would
     * already form a complete pair and not be orphans in the first place. Safe removal is the
     * only real option.
     */
    private fun sweepOrphanHalves(children: List<DocumentFile>) {
        val surviving = children.filter { it.isFile && it.exists() }
        val namesPresent = surviving.mapNotNull { it.name }.toSet()
        surviving.forEach { document ->
            val name = document.name ?: return@forEach
            when {
                BackupFileNames.isCompletedDatabaseName(name) -> {
                    val metadataName = name.removeSuffix(".db") + ".json"
                    if (metadataName !in namesPresent) {
                        runCatching { document.delete() }
                    }
                }
                name.endsWith(".json") && !name.endsWith(".json.partial") -> {
                    val databaseName = name.removeSuffix(".json") + ".db"
                    if (databaseName !in namesPresent || !BackupFileNames.isCompletedDatabaseName(databaseName)) {
                        runCatching { document.delete() }
                    }
                }
            }
        }
    }

    private fun completedPairs(
        children: List<DocumentFile>,
        tier: BackupTier,
    ): List<CompletedBackup<DocumentFile>> = CompletedBackupCatalog.enumerate(
        tier = tier,
        artifacts = children.map { document ->
            BackupArtifact(
                document = document,
                name = document.name.orEmpty(),
                isFile = document.isFile,
                sizeBytes = document.length(),
            )
        },
        readMetadata = { document -> readMetadata(document.uri) },
    )

    private fun readMetadata(uri: Uri): BackupMetadata? = runCatching {
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
