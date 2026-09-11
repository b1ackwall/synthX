package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
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
    val restoreIneligibleReason: String? = null,
)

class BackupNotConfiguredException : IllegalStateException("No backup folder selected")

/**
 * Whole-database backup and restore against a user-chosen SAF tree.
 *
 * Ordinary backups snapshot through Room's live SupportSQLiteDatabase. The migration reason is
 * deliberately special: it snapshots through a raw framework handle so opening Room cannot migrate
 * the source before its pre-migration state is captured.
 */
class BackupManager(private val context: Context) {

    private val preferences = PreferencesManager(context)

    // ---------------------------------------------------------------- create

    /**
     * @param cancelActiveScan pass `false` from inside [LibraryScanWorker]. The worker is itself the
     *   running work, so waiting for the unique work to reach a terminal state from within it would
     *   never return.
     * @return on success, the database filename (e.g. "synthx_backup_...db") of the backup just
     *   created — [BackupFileIdentity] itself stays internal, so only the filename crosses out.
     */
    suspend fun createBackup(
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
        val stagingDirectory = File(context.noBackupFilesDir, STAGING_DIRECTORY)
        val snapshot = File(stagingDirectory, "snapshot-${UUID.randomUUID()}.partial.db")

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

            val published = SafBackupPublisher(context).publish(
                tierDirectory = tierDirectory,
                identity = identity,
                snapshot = snapshot,
                digest = digest,
                metadataBytes = BackupMetadataCodec.encode(metadata),
            )

            // Publication is complete at this point. Preserve retention's current best-effort
            // policy so cleanup cannot turn a valid, published backup into a reported failure.
            runCatching { enforceRetention(tierDirectory, tier, published.identity) }
                .onFailure { Log.w(TAG, "Backup retention skipped", it) }
            identity.databaseFileName
        } finally {
            deleteLocalStage(snapshot)
        }
    }

    // --------------------------------------------------------------- listing

    /** All backups across every tier, newest first. Unreadable tiers are skipped. */
    suspend fun listBackups(): List<BackupInfo> = withContext(Dispatchers.IO) {
        val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) }
            ?: return@withContext emptyList()
        BackupTier.entries.flatMap { tier ->
            val directory = resolveTierDir(treeUri, tier, create = false)
                ?: return@flatMap emptyList()
            val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
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
                    val eligible = completePair?.metadata?.let { value ->
                        runCatching {
                            value.dbVersion <= SynthXDatabase.VERSION &&
                                BackupSchemaContracts.forVersion(value.dbVersion)?.hasMigrationPathToCurrent == true
                        }.getOrDefault(false)
                    } == true
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
                        restoreIneligibleReason = if (eligible) null else "Legacy backup — restore unavailable",
                    )
                }
        }.sortedByDescending { it.timestamp }
    }

    // ------------------------------------------------------------ verification (P3.2)

    /**
     * Re-validates the most recent successful backup's actual file — integrity_check,
     * foreign_key_check, Room identity hash, expected tables, via the same
     * [BackupSnapshotValidator] every backup is validated with at write time — catching
     * corruption a healthy write wouldn't have (bit rot, a provider that silently truncated
     * the file after publish, etc.). A no-op success if there is no backup yet: nothing to
     * verify is not a verification failure.
     *
     * Called from [com.v1per.synthx.MainActivity]'s existing once-per-day gate rather than a
     * second, uncoordinated timer — see `backupDailyIfDue`.
     */
    suspend fun verifyMostRecentBackup(): Result<Unit> = withContext(Dispatchers.IO) {
        val result = runCatching {
            val backup = listBackups().firstOrNull() ?: return@runCatching
            val stagingDirectory = File(context.noBackupFilesDir, STAGING_DIRECTORY)
            if (!stagingDirectory.exists()) stagingDirectory.mkdirs()
            val copy = File(stagingDirectory, "verify-${UUID.randomUUID()}.db")
            try {
                RestoreTransaction.copySafOnce(context, backup.databaseUri.toString(), copy)
                BackupSnapshotValidator.validate(copy, migrationSnapshot = false)
            } finally {
                copy.delete()
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
            val stagingDirectory = File(context.noBackupFilesDir, STAGING_DIRECTORY)
            if (!stagingDirectory.exists()) stagingDirectory.mkdirs()
            val copy = File(stagingDirectory, "preview-${UUID.randomUUID()}.db")
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
                deleteLocalStage(copy)
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
    suspend fun restore(backup: BackupInfo): Result<Unit> = withContext(Dispatchers.IO) {
        RestoreStartup.await()
        // A pending restore may itself be the first Worker in a new app process. Its safety
        // snapshot opens live Room, so it must cross C1 just like every other live Room owner.
        DatabaseStartupGate.awaitReady()
        val metadata = readRestoreMetadata(backup)
            ?: return@withContext Result.failure(IllegalArgumentException("Legacy backup — restore unavailable"))
        RestoreTransaction.execute(context, backup, metadata)
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

    /** Creates Daily/Auto/Manual under [treeUri] if absent. Safe to call repeatedly. */
    fun ensureFolderStructure(treeUri: Uri): Boolean {
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

    private fun enforceRetention(
        tierDirectory: DocumentFile,
        tier: BackupTier,
        protected: BackupFileIdentity,
    ) {
        val children = runCatching { tierDirectory.listFiles().toList() }.getOrDefault(emptyList())
        BackupRetention.prune(
            completed = completedPairs(children, tier),
            limit = tier.retention,
            protectedDatabaseName = protected.databaseFileName,
        ) { pair ->
            // Attempt both sides independently. If one fails, the survivor is an orphan and
            // cannot re-enter CompletedBackupCatalog on the next pass.
            runCatching { pair.database.document.delete() }
            runCatching { pair.metadataDocument.document.delete() }
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

    private fun deleteLocalStage(snapshot: File) {
        listOf(
            snapshot,
            File("${snapshot.path}-journal"),
            File("${snapshot.path}-wal"),
            File("${snapshot.path}-shm"),
        ).filter(File::exists).forEach { file ->
            if (!file.delete()) Log.w(TAG, "Could not remove local backup stage ${file.name}")
        }
    }

    companion object {
        private const val TAG = "BackupManager"
        private const val STAGING_DIRECTORY = "backup_staging"

        /** Manual-DI entry point, matching SongRepositoryImpl.create. */
        fun create(context: Context): BackupManager = BackupManager(context.applicationContext)
    }
}
