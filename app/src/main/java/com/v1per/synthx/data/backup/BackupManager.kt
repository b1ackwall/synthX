package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
)

class BackupNotConfiguredException : IllegalStateException("No backup folder selected")

/**
 * Whole-database backup and restore against a user-chosen SAF tree.
 *
 * Deliberately never touches the Room singleton: the checkpoint runs through a raw
 * [SQLiteDatabase] handle so that taking a backup cannot itself trigger a Room migration.
 * That is what makes the pre-migration snapshot in [BackupReason.MIGRATION] possible.
 */
class BackupManager(private val context: Context) {

    private val preferences = PreferencesManager(context)

    // ---------------------------------------------------------------- create

    /**
     * @param cancelActiveScan pass `false` from inside [LibraryScanWorker]. The worker is
     *   itself the running work, so waiting for the unique work to reach a terminal state
     *   from within it would never return.
     */
    suspend fun createBackup(
        tier: BackupTier,
        reason: String? = null,
        cancelActiveScan: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) }
                ?: throw BackupNotConfiguredException()

            val dbFile = context.getDatabasePath(SynthXDatabase.DB_NAME)
            if (!dbFile.exists()) error("No database to back up yet")

            if (cancelActiveScan) awaitScanIdle()

            checkpointWal(dbFile)

            val tierDir = resolveTierDir(treeUri, tier)
                ?: error("Backup folder is unreachable — re-select it in Library Management")

            val stamp = System.currentTimeMillis()
            val baseName = "synthx_backup_${FILE_STAMP.format(Date(stamp))}"

            val dbDoc = tierDir.createFile(MIME_DB, "$baseName.db")
                ?: error("Could not create backup file")
            context.contentResolver.openOutputStream(dbDoc.uri)?.use { out ->
                FileInputStream(dbFile).use { input -> input.copyTo(out) }
            } ?: error("Could not open backup file for writing")

            // Sidecar is best-effort: a backup without metadata is still restorable, and
            // listBackups falls back to the filename stamp.
            runCatching {
                val json = JSONObject()
                    .put(KEY_TIMESTAMP, stamp)
                    .put(KEY_TIER, tier.name)
                    .put(KEY_REASON, reason ?: JSONObject.NULL)
                    .put(KEY_DB_VERSION, SynthXDatabase.VERSION)
                    .put(KEY_FILE, "$baseName.db")
                tierDir.createFile(MIME_JSON, "$baseName.json")?.let { doc ->
                    context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                        out.write(json.toString().toByteArray())
                    }
                }
            }

            enforceRetention(tierDir, tier)
        }
    }

    // --------------------------------------------------------------- listing

    /** All backups across every tier, newest first. Unreadable tiers are skipped. */
    suspend fun listBackups(): List<BackupInfo> = withContext(Dispatchers.IO) {
        val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) } ?: return@withContext emptyList()
        BackupTier.entries.flatMap { tier ->
            val dir = resolveTierDir(treeUri, tier, create = false) ?: return@flatMap emptyList()
            val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
            val byName = children.associateBy { it.name.orEmpty() }

            children
                .filter { it.isFile && it.name?.endsWith(".db") == true }
                .map { dbDoc ->
                    val name = dbDoc.name.orEmpty()
                    val base = name.removeSuffix(".db")
                    val metaDoc = byName["$base.json"]
                    val meta = metaDoc?.let { readMetadata(it) }
                    BackupInfo(
                        databaseUri = dbDoc.uri,
                        metadataUri = metaDoc?.uri,
                        fileName = name,
                        tier = tier,
                        timestamp = meta?.optLong(KEY_TIMESTAMP)?.takeIf { it > 0 }
                            ?: parseStampFromName(base)
                            ?: dbDoc.lastModified(),
                        reason = meta?.optString(KEY_REASON)?.takeIf {
                            it.isNotBlank() && it != "null"
                        },
                        sizeBytes = dbDoc.length(),
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
        runCatching {
            awaitScanIdle()

            SynthXDatabase.closeInstance()

            val dbFile = context.getDatabasePath(SynthXDatabase.DB_NAME)
            // Stale sidecars would be replayed over the restored file and undo it.
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()

            context.contentResolver.openInputStream(backup.databaseUri)?.use { input ->
                FileOutputStream(dbFile).use { out -> input.copyTo(out) }
            } ?: error("Could not read the selected backup")
            Unit
        }
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

    /**
     * Cancels any running scan and waits for the unique work to settle. An empty work list
     * satisfies the predicate, so this returns immediately when no scan has ever run.
     */
    private suspend fun awaitScanIdle() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME)
        workManager.getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
            .first { infos -> infos.all { it.state.isFinished } }
    }

    /**
     * Forces the WAL into the main file so a copy of `synthx.db` alone is complete.
     * Opened raw rather than through Room — Room would run migrations on open, which is
     * exactly what the pre-migration backup needs to avoid.
     */
    private fun checkpointWal(dbFile: File) {
        runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { raw ->
                raw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        }
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

    /** Deletes oldest-first until the tier holds at most [BackupTier.retention] backups. */
    private fun enforceRetention(tierDir: DocumentFile, tier: BackupTier) {
        val children = runCatching { tierDir.listFiles().toList() }.getOrDefault(emptyList())
        val byName = children.associateBy { it.name.orEmpty() }
        val backups = children
            .filter { it.isFile && it.name?.endsWith(".db") == true }
            .sortedByDescending { doc ->
                parseStampFromName(doc.name.orEmpty().removeSuffix(".db")) ?: doc.lastModified()
            }

        backups.drop(tier.retention).forEach { doc ->
            val base = doc.name.orEmpty().removeSuffix(".db")
            runCatching { doc.delete() }
            byName["$base.json"]?.let { meta -> runCatching { meta.delete() } }
        }
    }

    private fun readMetadata(doc: DocumentFile): JSONObject? = runCatching {
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            JSONObject(input.readBytes().decodeToString())
        }
    }.getOrNull()

    private fun parseStampFromName(base: String): Long? = runCatching {
        FILE_STAMP.parse(base.removePrefix("synthx_backup_"))?.time
    }.getOrNull()

    companion object {
        private const val MIME_DB = "application/octet-stream"
        private const val MIME_JSON = "application/json"

        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_TIER = "tier"
        private const val KEY_REASON = "reason"
        private const val KEY_DB_VERSION = "dbVersion"
        private const val KEY_FILE = "file"

        private val FILE_STAMP = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

        /** Manual-DI entry point, matching SongRepositoryImpl.create. */
        fun create(context: Context): BackupManager = BackupManager(context.applicationContext)
    }
}
