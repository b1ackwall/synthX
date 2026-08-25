package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.SongDao
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
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
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
                runCatching { writeExport() }
                    .onFailure { e -> Log.w(TAG, "Playlist export skipped: ${e.message}") }
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
        val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) } ?: return@withContext

        val export = assembleExport()
        val bytes = json.encodeToString(PlaylistExport.serializer(), export).toByteArray()

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Backup folder is unreachable — re-select it in Backups and Restore")

        // Leftover from a previous run that crashed before reaching the rename below.
        root.findFile(TEMP_FILE_NAME)?.delete()

        val tempDoc = root.createFile(MIME_JSON, TEMP_FILE_NAME)
            ?: error("Could not create playlist export temp file")
        context.contentResolver.openOutputStream(tempDoc.uri)?.use { out -> out.write(bytes) }
            ?: error("Could not open playlist export temp file for writing")

        // Promote the fully-written temp file over the previous export. SAF's DocumentFile
        // has no atomic "replace" primitive, only per-document renameTo — so this is the
        // closest approximation available: the previous good export is parked under a .bak
        // name (never left with zero copies of *something* readable) and only dropped once
        // the new file has successfully taken the real name. A crash could still land between
        // the two renameTo calls, but both are near-instant metadata operations, unlike the
        // write above — the risk window they leave is far smaller than the one a direct
        // overwrite-in-place would have carried.
        root.findFile(BACKUP_FILE_NAME)?.delete()
        val previous = root.findFile(FINAL_FILE_NAME)
        if (previous != null && !previous.renameTo(BACKUP_FILE_NAME)) {
            error("Could not stage previous playlist export for replacement")
        }
        if (!tempDoc.renameTo(FINAL_FILE_NAME)) {
            error("Could not finalize playlist export")
        }
        root.findFile(BACKUP_FILE_NAME)?.delete()
        Unit
    }

    /**
     * Rebuilds the full export tree from scratch — always the complete set of playlists, never
     * an incremental patch, so there is nothing that can drift out of sync between writes.
     */
    private suspend fun assembleExport(): PlaylistExport {
        val ungroupedEntities = playlistDao.getUngroupedPlaylists().first()
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

        val songEntities = songDao.getSongsByIds(referencedSongIds)
        val artistRefsBySongId = songDao.getSongArtistRefsForSongs(referencedSongIds)
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

        fun toExportedPlaylist(id: String, name: String) =
            ExportedPlaylist(name = name, songHashes = songIdsByPlaylist[id].orEmpty())

        val groups = groupEntities.map { group ->
            ExportedGroup(
                name = group.name,
                playlists = playlistsByGroup[group].orEmpty()
                    .map { toExportedPlaylist(it.id, it.name) },
            )
        }
        val ungroupedPlaylists = ungroupedEntities.map { toExportedPlaylist(it.id, it.name) }

        return PlaylistExport(
            exportedAt = System.currentTimeMillis(),
            songs = songs,
            groups = groups,
            ungroupedPlaylists = ungroupedPlaylists,
        )
    }

    companion object {
        private const val TAG = "PlaylistExport"

        private const val MIME_JSON = "application/json"
        private const val FINAL_FILE_NAME = "playlists_export.json"
        private const val TEMP_FILE_NAME = "playlists_export.json.tmp"
        private const val BACKUP_FILE_NAME = "playlists_export.json.bak"

        private const val DEBOUNCE_MS = 800L

        /** Manual-DI entry point, matching BackupManager.create. */
        fun create(context: Context): PlaylistExportManager {
            val appContext = context.applicationContext
            val db = SynthXDatabase.getInstance(appContext)
            return PlaylistExportManager(
                context = appContext,
                playlistDao = db.playlistDao(),
                songDao = db.songDao(),
                artistDao = db.artistDao(),
                albumDao = db.albumDao(),
            )
        }
    }
}
