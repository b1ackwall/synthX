package com.v1per.synthx.data.backup

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.queryInBatches
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Keeps [PlaylistExport] — a human-readable JSON mirror of playlist structure — up to date in
 * the same SAF-external tree [BackupManager] writes whole-DB snapshots to. Deliberately
 * separate from [BackupManager]: this file is meant to be readable and reconstructable by a
 * disaster-recovery script with no access to the app or `synthx.db` at all.
 *
 * One instance is created per process, alongside the [com.v1per.synthx.data.repository.SongRepositoryImpl]
 * singleton that owns it (see its `create` factory) — [scope] is deliberately never cancelled,
 * matching that singleton's own `repositoryScope`, since [requests] must keep being collected
 * for the lifetime of the app for the debounce below to ever fire.
 */
class PlaylistExportManager(
    private val context: Context,
    private val database: SynthXDatabase,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    // Deterministic boundary seam for the Room snapshot regression test.
    private val materializationCheckpoint: suspend () -> Unit = {},
) {

    private val preferences = PreferencesManager(context)
    private val json = Json { prettyPrint = true }

    // extraBufferCapacity = 1 + DROP_OLDEST: scheduleExport() must never suspend or block the
    // mutation method calling it — a full buffer just collapses into "one more export is
    // pending" rather than back-pressuring the caller.
    private val requests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // SupervisorJob so one failed export (caught below anyway) can never cancel the collector
    // itself and silently stop future exports from ever running again.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        observeRequests()
    }

    @OptIn(FlowPreview::class)
    private fun observeRequests() {
        scope.launch {
            requests.debounce(DEBOUNCE_MS).collect {
                // No folder configured is a no-op, same as writeExport's own early return —
                // not an attempt worth recording, since nothing was attempted.
                if (preferences.backupFolderUri == null) return@collect

                val result = runCatching { writeExport() }
                result.onFailure { e -> Log.w(TAG, "Playlist export skipped: ${e.message}") }
                // writeExport() is the single choke point every export request funnels
                // through (scheduleExport only enqueues), so recording here covers all of them.
                BackupOutcomeStore.recordExportOutcome(context, result)
            }
        }
    }

    /**
     * Call after any playlist/group mutation commits. Fire-and-forget: returns immediately,
     * the actual assemble-and-write happens on [scope] after [DEBOUNCE_MS] of quiet, coalescing
     * a burst of rapid mutations (e.g. adding many songs in a row) into one write.
     */
    fun scheduleExport() {
        requests.tryEmit(Unit)
    }

    private suspend fun writeExport(): Unit = withContext(Dispatchers.IO) {
        val treeUri = preferences.backupFolderUri?.let { it.toUri() } ?: return@withContext

        // The database materialization is a finite tracked operation; SAF writing happens
        // afterwards, so it does not keep Room alive during restore preparation.
        val export = materializeExport()
        val bytes = json.encodeToString(PlaylistExport.serializer(), export).toByteArray()

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Backup folder is unreachable — re-select it in Backups and Restore")

        publishExport(root, bytes)
    }

    /**
     * Writes [bytes] as the current export inside the [EXPORT_FOLDER_NAME] subfolder of [root],
     * migrating any pre-subfolder root-level files first. `internal`: a test seam so the
     * folder/migration/rotation behavior can run against a file-backed [DocumentFile] root
     * (see [BackupManager.classifyBackupList] for why a genuine SAF tree is unavailable in tests).
     */
    internal fun publishExport(root: DocumentFile, bytes: ByteArray) {
        val dir = resolveExportDir(root)
            ?: error("Could not create the $EXPORT_FOLDER_NAME folder in the backup folder")

        if (!legacyMigrationDone) {
            migrateLegacyRootFiles(root, dir)
            legacyMigrationDone = true
        }

        // Leftover from a previous run that crashed before reaching the rename below.
        dir.findFile(TEMP_FILE_NAME)?.delete()

        val tempDoc = dir.createFile(MIME_JSON, TEMP_FILE_NAME)
            ?: error("Could not create playlist export temp file")
        context.contentResolver.openOutputStream(tempDoc.uri)?.use { out -> out.write(bytes) }
            ?: error("Could not open playlist export temp file for writing")

        // Rotate existing generations up (oldest pruned, the rest shifted by one, current
        // moved to generation .1) BEFORE promoting the new write. This ordering avoids a
        // both-copies-missing race: the previous "current" file is only ever renamed here,
        // never deleted, ahead of the promotion below — so even if the final rename fails,
        // generation .1 still holds the previous good export. The only unconditional deletion
        // is of the oldest generation being pruned, whose content is already duplicated by
        // every generation below it plus the (still-untouched) current file, so pruning it can
        // never leave fewer than GENERATIONS_KEPT recoverable copies on disk.
        rotateGenerations(dir)

        // Promote the fully-written temp file into the now-vacated current slot. SAF's
        // DocumentFile has no atomic "replace" primitive, only per-document renameTo, but the
        // rotation above already guarantees a fallback copy exists regardless of what happens
        // next.
        if (!tempDoc.renameTo(FINAL_FILE_NAME)) {
            error("Could not finalize playlist export")
        }
    }

    /** Same find-or-create shape as BackupManager.resolveTierDir, but not a [BackupTier]. */
    private fun resolveExportDir(root: DocumentFile): DocumentFile? {
        val existing = root.findFile(EXPORT_FOLDER_NAME)
        if (existing != null && existing.isDirectory) return existing
        return root.createDirectory(EXPORT_FOLDER_NAME)
    }

    /**
     * One-time move of `playlists_export.json` and its numbered generations from the backup
     * root (where they lived before the subfolder existed) into [dir], keeping filenames so the
     * generation history carries forward. DocumentFile has no cross-directory move, so each is
     * copied to a `.migrating` temp, renamed into place, then the root original is deleted — a
     * crash at any point leaves the original intact. A file already present in [dir] is never
     * overwritten: the root original is only deleted if the copy there is the same size,
     * otherwise it is left in place (no data loss, retried next process). A root-level
     * `.tmp` is a crashed partial write and is just deleted.
     */
    internal fun migrateLegacyRootFiles(root: DocumentFile, dir: DocumentFile) {
        root.findFile(TEMP_FILE_NAME)?.delete()
        val names = listOf(FINAL_FILE_NAME) + (1..GENERATIONS_KEPT).map(::generationFileName)
        for (name in names) {
            val legacy = root.findFile(name)?.takeIf { it.isFile } ?: continue
            val existing = dir.findFile(name)
            if (existing != null) {
                if (existing.length() == legacy.length()) legacy.delete()
                continue
            }
            val stagingName = "$name.migrating"
            dir.findFile(stagingName)?.delete()
            val staging = dir.createFile(MIME_JSON, stagingName) ?: continue
            val copied = runCatching {
                context.contentResolver.openInputStream(legacy.uri)?.use { input ->
                    context.contentResolver.openOutputStream(staging.uri)?.use { output ->
                        input.copyTo(output)
                    } ?: return@runCatching false
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            if (copied && staging.length() == legacy.length() && staging.renameTo(name)) {
                legacy.delete()
            } else {
                staging.delete()
            }
        }
    }

    private fun rotateGenerations(dir: DocumentFile) {
        dir.findFile(generationFileName(GENERATIONS_KEPT))?.delete()
        for (generation in GENERATIONS_KEPT - 1 downTo 1) {
            dir.findFile(generationFileName(generation))?.renameTo(generationFileName(generation + 1))
        }
        dir.findFile(FINAL_FILE_NAME)?.renameTo(generationFileName(1))
    }

    private fun generationFileName(generation: Int) = "playlists_export.$generation.json"

    /**
     * Rebuilds the full export tree from scratch — always the complete set of playlists, never
     * an incremental patch, so there is nothing that can drift out of sync between writes.
     */
    private suspend fun assembleExport(): PlaylistExport {
        val ungroupedEntities = playlistDao.getUngroupedPlaylists().first()
        materializationCheckpoint()
        val groupEntities = playlistDao.getAllGroups().first()
        val playlistsByGroup = groupEntities.associateWith { group ->
            playlistDao.getPlaylistsForGroup(group.id).first()
        }

        val allPlaylists = ungroupedEntities + playlistsByGroup.values.flatten()

        // playlist_songs directly, unfiltered by is_missing — unlike getSongsForPlaylist,
        // this must keep a missing song's slot in the ordering too.
        val songIdsByPlaylist = allPlaylists.associate { playlist ->
            playlist.id to playlistDao.getSongIdsForPlaylist(playlist.id)
        }

        val referencedSongIds = songIdsByPlaylist.values.flatten().distinct()

        val songEntities = referencedSongIds.queryInBatches(songDao::getSongsByIds)
        val artistRefsBySongId = referencedSongIds.queryInBatches(songDao::getSongArtistRefsForSongs)
            .groupBy { it.songId }
        val artistNameById = artistDao.getAllArtistsOnce().associate { it.id to it.name }
        val albumTitleById = albumDao.getAllAlbumsOnce().associate { it.id to it.title }

        val songs = songEntities.associate { song ->
            val artistNames = (artistRefsBySongId[song.id] ?: emptyList())
                .sortedBy { it.position }
                .mapNotNull { artistNameById[it.artistId] }
            song.id to ExportedSong(
                title = song.title,
                artists = artistNames,
                album = song.albumId?.let { albumTitleById[it] },
                filePath = song.filePath,
                isMissing = song.isMissing,
            )
        }

        fun toExportedPlaylist(playlist: PlaylistEntity, position: Int) = ExportedPlaylist(
            name = playlist.name,
            description = playlist.description,
            createdAt = playlist.createdAt,
            updatedAt = playlist.updatedAt,
            position = position,
            songHashes = songIdsByPlaylist[playlist.id].orEmpty(),
        )

        val groups = groupEntities.mapIndexed { index, group ->
            ExportedGroup(
                name = group.name,
                position = index,
                playlists = playlistsByGroup[group].orEmpty()
                    .mapIndexed { playlistIndex, playlist -> toExportedPlaylist(playlist, playlistIndex) },
            )
        }
        val ungroupedPlaylists = ungroupedEntities.mapIndexed { index, playlist ->
            toExportedPlaylist(playlist, index)
        }

        return PlaylistExport(
            exportedAt = System.currentTimeMillis(),
            hashStrategy = HASH_STRATEGY,
            songs = songs,
            groups = groups,
            ungroupedPlaylists = ungroupedPlaylists,
        )
    }

    private suspend fun materializeExport(): PlaylistExport = PlaylistExportMaterialization.run {
        database.withTransaction { assembleExport() }
    }

    /** Exercises the exact production materialization boundary without requiring a SAF tree. */
    internal suspend fun materializeExportForTest(): PlaylistExport = materializeExport()

    companion object {
        private const val TAG = "PlaylistExport"

        private const val MIME_JSON = "application/json"
        /** Sibling of the Daily/Auto/Manual tier folders; deliberately not a [BackupTier]. */
        internal const val EXPORT_FOLDER_NAME = "PlaylistExports"
        private const val FINAL_FILE_NAME = "playlists_export.json"
        private const val TEMP_FILE_NAME = "playlists_export.json.tmp"

        /** How many prior generations survive alongside the current export, see [rotateGenerations]. */
        private const val GENERATIONS_KEPT = 5

        /** Sampled SHA-256 song fingerprint (HashUtils) — see [PlaylistExport.hashStrategy]. */
        private const val HASH_STRATEGY = "synthx_sampled_sha256_v1"

        private const val DEBOUNCE_MS = 800L

        @Volatile private var legacyMigrationDone = false

        /** Test-only: resets the once-per-process migration guard. */
        internal fun resetMigrationGuardForTest() { legacyMigrationDone = false }

        // Only applicationContext is stored here (see create).
        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: PlaylistExportManager? = null

        // A true singleton (matching SongRepositoryImpl.create()'s and SynthXDatabase's own
        // pattern), not just a caller-discipline convention: a second independent instance
        // would run its own competing debounced-write collector against the same SAF files.
        fun create(context: Context): PlaylistExportManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val db = SynthXDatabase.getInstance(appContext)
                    PlaylistExportManager(
                        context = appContext,
                        database = db,
                        playlistDao = db.playlistDao(),
                        songDao = db.songDao(),
                        artistDao = db.artistDao(),
                        albumDao = db.albumDao(),
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}

/** The exact finite Room boundary used by every playlist export. */
internal object PlaylistExportMaterialization {
    suspend fun <T> run(block: suspend () -> T): T = RestoreCoordinator.withDbOperation(block)
}
