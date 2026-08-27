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
    const val RESCAN = "rescan"
    const val PRE_RESTORE_SAFETY = "pre_restore_safety"
}

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
     */
    suspend fun createBackup(
        tier: BackupTier,
        reason: String? = null,
        cancelActiveScan: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            RestoreStartup.await()
            RestoreCoordinator.withDbOperation {
                BackupCreationCoordinator.serialize {
                    createBackupLocked(tier, reason, cancelActiveScan)
                }
            }
        }
    }

    /**
     * C1's only production pre-migration entry point. It retains the complete Phase-A pipeline,
     * but cannot wait for/cancel the Worker that may itself be the process's first gate caller.
     * [BackupReason.MIGRATION] selects [BackupSnapshotter.createForMigration], so this path never
     * obtains Room while the gate is still closed.
     */
    internal suspend fun createPreMigrationBackup(): Result<Unit> = createBackup(
        tier = BackupTier.Auto,
        reason = BackupReason.MIGRATION,
        cancelActiveScan = false,
    )

    private suspend fun createBackupLocked(
        tier: BackupTier,
        reason: String?,
        cancelActiveScan: Boolean,
    ) {
        val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) }
            ?: throw BackupNotConfiguredException()
        val sourceFile = context.getDatabasePath(SynthXDatabase.DB_NAME)
        if (!sourceFile.isFile) error("No database to back up yet")

        if (cancelActiveScan) awaitScanIdle()

        val tierDirectory = resolveTierDir(treeUri, tier)
            ?: error("Backup folder is unreachable — re-select it in Backups and Restore")
        val stagingDirectory = File(context.noBackupFilesDir, STAGING_DIRECTORY)
        val snapshot = File(stagingDirectory, "snapshot-${UUID.randomUUID()}.partial.db")

        try {
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

            SafBackupPublisher(context).publish(
                tierDirectory = tierDirectory,
                identity = identity,
                snapshot = snapshot,
                digest = digest,
                metadataBytes = BackupMetadataCodec.encode(metadata),
            )

            // Publication is complete at this point. Preserve retention's current best-effort
            // policy so cleanup cannot turn a valid, published backup into a reported failure.
            runCatching { enforceRetention(tierDirectory, tier) }
                .onFailure { Log.w(TAG, "Backup retention skipped", it) }
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

            children
                .filter { document ->
                    document.isFile &&
                        document.name?.let(BackupFileNames::isCompletedDatabaseName) == true
                }
                .map { databaseDocument ->
                    val name = databaseDocument.name.orEmpty()
                    val stem = name.removeSuffix(".db")
                    val metadataDocument = byName["$stem.json"]
                    val metadata = metadataDocument?.let { readMetadata(it.uri) }
                    val eligible = metadata?.let { value ->
                        runCatching {
                            value.requireCompleteForPublication()
                            value.file == name && value.dbVersion <= SynthXDatabase.VERSION &&
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

    /** Existing retention counts only recognizable final database documents. */
    private fun enforceRetention(tierDirectory: DocumentFile, tier: BackupTier) {
        val children = runCatching { tierDirectory.listFiles().toList() }.getOrDefault(emptyList())
        val byName = children.associateBy { it.name.orEmpty() }
        val backups = children
            .filter { document ->
                document.isFile &&
                    document.name?.let(BackupFileNames::isCompletedDatabaseName) == true
            }
            .sortedByDescending { document ->
                BackupFileNames.parseTimestampMillis(document.name.orEmpty())
                    ?: document.lastModified()
            }

        backups.drop(tier.retention).forEach { document ->
            val stem = document.name.orEmpty().removeSuffix(".db")
            runCatching { document.delete() }
            byName["$stem.json"]?.let { metadata -> runCatching { metadata.delete() } }
        }
    }

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
