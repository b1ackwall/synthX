package com.v1per.synthx.data.repository

import android.content.Context
import android.net.Uri
import com.v1per.synthx.BuildConfig
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.QueueDao
import com.v1per.synthx.data.db.SongArtistEntity
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongWithDetails
import com.v1per.synthx.data.db.SynthXDatabase
import android.util.Log
import androidx.room.withTransaction
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.RootOrganizationCheckResult
import com.v1per.synthx.data.scanner.ScanResult
import com.v1per.synthx.data.scanner.walkAndClassifyRootOrganization
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.preferences.DestructiveActionTracker
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val TAG = "SongRepositoryImpl"

class SongRepositoryImpl(
    private val context: Context,
    private val database: SynthXDatabase,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val playlistDao: PlaylistDao,
    private val queueDao: QueueDao,
    private val folderScanner: FolderScanner,
    private val backupManager: BackupManager,
    private val playlistExportManager: PlaylistExportManager,
) : SongRepository, AdmittedQueuePersistenceRepository {

    private suspend fun <T> dbOperation(block: suspend () -> T): T =
        RepositoryDbOperation.run(block)

    // This class is a singleton (see companion.create), so this scope effectively lives as
    // long as the app process does — same shape as ExoPlayerEngine.engineScope /
    // QueuePersistence.scope at their own instance-lifetime level.
    private val repositoryJob = SupervisorJob()
    private val repositoryScope = CoroutineScope(repositoryJob + Dispatchers.Default)

    // Shared across every getSongs() subscriber instead of re-querying and re-mapping the
    // full library (~1500 songs) on every SongsViewModel instance. Lazily rather than
    // WhileSubscribed: local song data changes rarely and Songs is a frequently-revisited
    // screen, so once the query has run once it's worth keeping warm for the rest of the
    // process — instant navigation matters more here than releasing this one cache early.
    // Room's Flow still auto-invalidates on real table changes, so this stays correct
    // across scans/edits regardless of how long the cache is held.
    // general 7.3: nothing upstream of this .stateIn can ever catch a failure here — once the
    // upstream Flow (including the extra getAllSongArtistRefs() query below) throws, the
    // stateIn producer coroutine dies and no downstream .catch{} on this StateFlow's consumers
    // can ever see it. The .catch below is the only point that can intercept it: it logs, then
    // degrades to the last successfully computed song list rather than crashing the process or
    // freezing on an empty list. The producer coroutine still ends after that — Room's live
    // invalidation is not resumed, so this is a one-time degrade-to-last-known-good, not a
    // recoverable retry; a fresh app process is what actually restores live updates.
    private var lastKnownSongs: List<Song> = emptyList()
    private val songsFlow: StateFlow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getAllSongsWithDetails() }.map { entries ->
            // One refs query per emission — 2 queries total regardless of library size
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
            entries.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
                .also { lastKnownSongs = it }
        }.catch { e ->
            Log.e(TAG, "songsFlow upstream failure — degrading to last known songs", e)
            emit(lastKnownSongs)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getSongs(): Flow<List<Song>> = songsFlow

    // Same caching rationale as songsFlow above — shared across every getArtists()
    // subscriber (Artists list, Search) instead of re-querying per collector.
    // See songsFlow above for why the .catch must sit here, upstream of .stateIn.
    private var lastKnownArtists: List<Artist> = emptyList()
    private val artistsFlow: StateFlow<List<Artist>> =
        RepositoryDbFlow.observe { artistDao.getAllArtists() }.map { entities ->
            entities.map { Artist(id = it.id, name = it.name) }.also { lastKnownArtists = it }
        }.catch { e ->
            Log.e(TAG, "artistsFlow upstream failure — degrading to last known artists", e)
            emit(lastKnownArtists)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getArtists(): Flow<List<Artist>> = artistsFlow

    // Sort order (title) stays in the DAO query; the Flow conversion preserves it.
    override fun getSongsForArtist(artistId: String): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getSongsWithDetailsForArtist(artistId) }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    // Same caching rationale as songsFlow above — shared across every getAlbums()
    // subscriber (Albums list, Search) instead of re-querying per collector.
    // See songsFlow above for why the .catch must sit here, upstream of .stateIn.
    private var lastKnownAlbums: List<Album> = emptyList()
    private val albumsFlow: StateFlow<List<Album>> =
        RepositoryDbFlow.observe { albumDao.getAllAlbums() }.map { entries ->
            entries.map {
                Album(
                    id = it.id,
                    title = it.title,
                    artist = it.artistName,
                    albumArtUri = it.albumArtUri,
                )
            }.also { lastKnownAlbums = it }
        }.catch { e ->
            Log.e(TAG, "albumsFlow upstream failure — degrading to last known albums", e)
            emit(lastKnownAlbums)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getAlbums(): Flow<List<Album>> = albumsFlow

    // Sort order (track number, then title) stays in the DAO query.
    override fun getSongsForAlbum(albumId: String): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getSongsWithDetailsForAlbum(albumId) }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    override suspend fun scanLibrary(
        folderUri: Uri,
        reconcileMissing: Boolean,
        otherRootUris: List<Uri>,
        isNewArrivalsRoot: Boolean,
        onProgress: suspend (current: Int, total: Int) -> Unit,
    ): ScanResult = dbOperation {
        scanLibraryAlreadyAdmitted(folderUri, reconcileMissing, otherRootUris, isNewArrivalsRoot, onProgress)
    }

    internal suspend fun scanLibraryAlreadyAdmitted(
        folderUri: Uri,
        reconcileMissing: Boolean = true,
        otherRootUris: List<Uri> = emptyList(),
        isNewArrivalsRoot: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = folderScanner.scanAlreadyAdmitted(
        folderUri,
        reconcileMissing,
        otherRootUris,
        isNewArrivalsRoot,
        onProgress,
    )

    override suspend fun verifyLibrary(configuredRootUris: List<Uri>): Int =
        dbOperation { folderScanner.verifyAlreadyAdmitted(configuredRootUris) }

    internal suspend fun verifyLibraryAlreadyAdmitted(configuredRootUris: List<Uri>): Int =
        folderScanner.verifyAlreadyAdmitted(configuredRootUris)

    /** Total library size, for gauging a scan's impact as a percentage. */
    internal suspend fun getSongCountAlreadyAdmitted(): Int = songDao.getSongCount()

    /**
     * Permanently removes a song. Its song_artists and playlist_songs rows go with it via
     * FK CASCADE — nothing here deletes them explicitly.
     *
     * The album-art re-point + row delete (via [deleteSongRowWithArtRepoint]) share one
     * transaction; the art file is unlinked only after it commits, since a file delete
     * cannot be rolled back alongside the DB. Same shared cleanup FolderScanner's internal
     * evictions use, so both deletion paths stay in sync.
     */
    override suspend fun deleteSong(id: String) = dbOperation { withContext(Dispatchers.IO) {
        // Backup-then-act: a failed backup (no folder configured) must not block the
        // delete the user explicitly asked for.
        backupManager.createBackup(BackupTier.Auto, BackupReason.DELETE_SONG)

        database.withTransaction {
            deleteSongRowWithArtRepoint(songDao, albumDao, id)
        }

        deleteSongArtFile(context, id)
        DestructiveActionTracker.recordDestructiveAction(context)
        Unit
    } }

    // Sort order (date_added DESC) and the starred/missing filters stay in the DAO query;
    // this only resolves artist order, same as the playlist/album/artist song lists above.
    override fun getStarredSongs(): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getStarredSongs() }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    override fun getStarredSongCount(): Flow<Int> =
        RepositoryDbFlow.observe { songDao.getStarredSongCount() }

    // Sort order (date_added DESC) and the limit/missing filter stay in the DAO query;
    // this only resolves artist order, same as the other scoped song lists above.
    override fun getRecentSongs(limit: Int): Flow<List<Song>> =
        RepositoryDbFlow.observe {
            songDao.getRecentSongsWithDetails(limit)
        }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    // No backupManager.createBackup here, unlike deleteSong/deletePlaylist: this clears one
    // boolean and destroys nothing, so there is nothing to restore from.
    override suspend fun dismissFromStarred(songId: String) = dbOperation {
        songDao.dismissFromStarred(songId)
    }

    override suspend fun isInAnyPlaylist(songId: String): Boolean = dbOperation {
        songDao.isInAnyPlaylist(songId)
    }

    // Debug tooling, read-only — no backupManager.createBackup here, unlike deleteSong: this
    // pass never writes to the DB.
    override suspend fun checkRootLibraryOrganization(musicFolderUri: Uri): RootOrganizationCheckResult? =
        dbOperation {
            walkAndClassifyRootOrganization(context, songDao, musicFolderUri)
        }

    override fun getMissingSongs(): Flow<List<MissingSongInfo>> =
        RepositoryDbFlow.observe { songDao.getMissingSongs() }

    override fun getMissingSongCount(): Flow<Int> =
        RepositoryDbFlow.observe { songDao.getMissingSongCount() }

    override fun getAllGroups(): Flow<List<PlaylistGroup>> =
        RepositoryDbFlow.observe { playlistDao.getAllGroups() }.map { entities ->
            entities.map { PlaylistGroup(id = it.id, name = it.name) }
        }

    override suspend fun createGroup(name: String) = dbOperation {
        playlistDao.insertGroup(
            PlaylistGroupEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                position = 0,
                createdAt = System.currentTimeMillis(),
            )
        )
        playlistExportManager.scheduleExport()
    }

    override suspend fun renameGroup(id: String, name: String) = dbOperation {
        playlistDao.renameGroup(id, name)
        playlistExportManager.scheduleExport()
    }

    override suspend fun deleteGroup(id: String) = dbOperation {
        playlistDao.deleteGroup(id)
        playlistExportManager.scheduleExport()
    }

    // Same caching rationale as songsFlow above — shared across every getAllPlaylists()
    // subscriber (SongActionsHost, Search) instead of re-querying per collector.
    // See songsFlow above for why the .catch must sit here, upstream of .stateIn.
    private var lastKnownPlaylists: List<Playlist> = emptyList()
    private val allPlaylistsFlow: StateFlow<List<Playlist>> =
        RepositoryDbFlow.observe { playlistDao.getAllPlaylists() }.map { entities ->
            entities.map { it.toDomain() }.also { lastKnownPlaylists = it }
        }.catch { e ->
            Log.e(TAG, "allPlaylistsFlow upstream failure — degrading to last known playlists", e)
            emit(lastKnownPlaylists)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getAllPlaylists(): Flow<List<Playlist>> = allPlaylistsFlow

    override fun getUngroupedPlaylists(): Flow<List<Playlist>> =
        RepositoryDbFlow.observe { playlistDao.getUngroupedPlaylists() }.map { entities ->
            entities.map { it.toDomain() }
        }

    override fun getPlaylistsForGroup(groupId: String): Flow<List<Playlist>> =
        RepositoryDbFlow.observe { playlistDao.getPlaylistsForGroup(groupId) }.map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun createPlaylist(name: String, groupId: String?): String = dbOperation {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        playlistDao.insertPlaylist(
            PlaylistEntity(
                id = id,
                name = name,
                description = null,
                groupId = groupId,
                position = 0,
                createdAt = now,
                updatedAt = now,
                coverArtUri = null,
            )
        )
        playlistExportManager.scheduleExport()
        id
    }

    override suspend fun renamePlaylist(id: String, name: String) = dbOperation {
        playlistDao.renamePlaylist(id, name, System.currentTimeMillis())
        playlistExportManager.scheduleExport()
    }

    override suspend fun movePlaylistToGroup(id: String, groupId: String?) = dbOperation {
        playlistDao.movePlaylistToGroup(id, groupId, System.currentTimeMillis())
        playlistExportManager.scheduleExport()
    }

    // Hooked here rather than in the ViewModels because both PlaylistListViewModel and
    // GroupDetailViewModel delete playlists through this one call.
    override suspend fun deletePlaylist(id: String) = dbOperation {
        backupManager.createBackup(BackupTier.Auto, BackupReason.DELETE_PLAYLIST)
        playlistDao.deletePlaylist(id)
        playlistExportManager.scheduleExport()
        DestructiveActionTracker.recordDestructiveAction(context)
    }

    // Joins straight to playlist_songs (position order, is_missing filter) rather than
    // re-fetching/joining the whole library and filtering down in Kotlin.
    override fun getSongsForPlaylist(playlistId: String): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getSongsWithDetailsForPlaylist(playlistId) }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    override suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean = dbOperation {
        // P7.6/N9/6.6: contains-check, max-position read, and insert live in one
        // @Transaction DAO method so concurrent adds to the same playlist can't race on
        // the next position — see PlaylistDao.addSongToPlaylist.
        val added = playlistDao.addSongToPlaylist(playlistId, songId, System.currentTimeMillis())
        if (added) {
            playlistExportManager.scheduleExport()
        }
        added
    }

    override suspend fun removeSongFromPlaylist(playlistId: String, songId: String) = dbOperation {
        playlistDao.removePlaylistSong(playlistId, songId)
        playlistExportManager.scheduleExport()
    }

    override suspend fun getSongsByIds(ids: List<String>): List<Song> = dbOperation { withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val details = songDao.getSongsWithDetailsByIds(ids)
        val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
            .groupBy { it.songId }
        details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    } }

    override suspend fun getQueueSongs(): List<Song> = dbOperation { withContext(Dispatchers.IO) {
        val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
        queueDao.getQueueItems().map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    } }

    override suspend fun getQueueSongIds(): List<String> = dbOperation { withContext(Dispatchers.IO) {
        queueDao.getQueueSongIds()
    } }

    override suspend fun getQueueSongIdsAndCursor(): Pair<List<String>, QueueCursor?> =
        dbOperation { withContext(Dispatchers.IO) {
            val (ids, cursor) = queueDao.getQueueAndCursor()
            ids to cursor?.let {
                QueueCursor(
                    index = it.queueIndex,
                    positionMs = it.positionMs,
                    shuffleEnabled = it.shuffleEnabled,
                    repeatMode = it.repeatMode,
                )
            }
        } }

    override suspend fun setQueueSongs(songIds: List<String>) = dbOperation { withContext(Dispatchers.IO) {
        queueDao.replaceQueue(songIds)
    } }

    override suspend fun setQueueStateAlreadyAdmitted(
        songIds: List<String>,
        queueIndex: Int,
        positionMs: Long,
    ) = withContext(Dispatchers.IO) {
        queueDao.replaceQueueAndCursor(songIds, queueIndex, positionMs)
        // P6.3: the one place both sides are now guaranteed to have landed together —
        // a violation here means replaceQueueAndCursor's transaction itself is broken,
        // never a legitimate runtime state, so this must never silently pass in debug.
        if (BuildConfig.DEBUG) {
            check(queueIndex < 0 || queueIndex < songIds.size) {
                "Playback cursor invariant violated: queueIndex=$queueIndex not in bounds " +
                    "of ${songIds.size} just-persisted queue items"
            }
        }
    }

    override suspend fun setCursorAlreadyAdmitted(
        queueIndex: Int,
        positionMs: Long,
    ) = withContext(Dispatchers.IO) {
        queueDao.updateCursor(queueIndex, positionMs)
    }

    override suspend fun setShuffleEnabledAlreadyAdmitted(enabled: Boolean) = withContext(Dispatchers.IO) {
        queueDao.updateShuffleEnabled(enabled)
    }

    override suspend fun setRepeatModeAlreadyAdmitted(mode: Int) = withContext(Dispatchers.IO) {
        queueDao.updateRepeatMode(mode)
    }

    override suspend fun getBaselineQueueSongs(): List<Song> = dbOperation { withContext(Dispatchers.IO) {
        val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
        queueDao.getBaselineQueueItems().map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    } }

    override suspend fun getBaselineQueueSongIds(): List<String> = dbOperation { withContext(Dispatchers.IO) {
        queueDao.getBaselineQueueSongIds()
    } }

    override suspend fun setBaselineQueueSongs(songIds: List<String>) = dbOperation { withContext(Dispatchers.IO) {
        queueDao.replaceBaselineQueue(songIds)
    } }

    override suspend fun setBaselineQueueSongsAlreadyAdmitted(songIds: List<String>) = withContext(Dispatchers.IO) {
        queueDao.replaceBaselineQueue(songIds)
    }

    private fun PlaylistEntity.toDomain(): Playlist =
        Playlist(
            id = id,
            name = name,
            groupId = groupId,
            albumArtUri = coverArtUri,
        )

    private fun SongWithDetails.toDomain(refs: List<SongArtistEntity>): Song {
        // @Relation via Junction returns artists unordered — restore song_artists.position
        val positionByArtistId = refs.associate { it.artistId to it.position }
        val orderedArtists = artists.sortedBy { positionByArtistId[it.id] ?: Int.MAX_VALUE }
        val artist = if (orderedArtists.isEmpty()) {
            "Unknown Artist"
        } else {
            orderedArtists.joinToString(", ") { it.name }
        }
        return Song(
            id = song.id,
            title = song.title,
            artist = artist,
            album = album?.title ?: "Unknown Album",
            duration = song.duration,
            filePath = song.filePath,
            albumArtUri = song.albumArtUri,
            albumArtFullUri = song.albumArtFullUri,
            isMissing = song.isMissing,
            dateAdded = song.dateAdded,
            albumId = song.albumId,
            // Same ordered list the display string is built from — no extra query.
            artists = orderedArtists.map { Artist(id = it.id, name = it.name) },
        )
    }

    companion object {
        @Volatile private var INSTANCE: SongRepositoryImpl? = null

        // Manual DI entry point — everything hangs off the SynthXDatabase singleton. A true
        // singleton itself now (matching SynthXDatabase.getInstance()'s pattern) so getSongs()'s
        // cached StateFlow above is actually shared across every caller rather than rebuilt
        // per-caller.
        fun create(context: Context): SongRepositoryImpl {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val db = SynthXDatabase.getInstance(appContext)
                    val playlistExportManager = PlaylistExportManager.create(appContext)
                    SongRepositoryImpl(
                        context = appContext,
                        database = db,
                        songDao = db.songDao(),
                        artistDao = db.artistDao(),
                        albumDao = db.albumDao(),
                        playlistDao = db.playlistDao(),
                        queueDao = db.queueDao(),
                        folderScanner = FolderScanner(
                            context = appContext,
                            database = db,
                            songDao = db.songDao(),
                            artistDao = db.artistDao(),
                            albumDao = db.albumDao(),
                            songArtistDao = db.songArtistDao(),
                            playlistExportManager = playlistExportManager,
                        ),
                        backupManager = BackupManager.create(appContext),
                        playlistExportManager = playlistExportManager,
                    ).also { INSTANCE = it }
                }
            }
        }

        /** Stops long-lived Room flows before restore closes the singleton database. */
        suspend fun quiesceForRestore() {
            val repository = synchronized(this) {
                val current = INSTANCE
                INSTANCE = null
                current
            }
            repository?.repositoryJob?.cancelAndJoin()
        }

        internal fun resetForTest() {
            synchronized(this) { INSTANCE = null }
        }
    }
}
