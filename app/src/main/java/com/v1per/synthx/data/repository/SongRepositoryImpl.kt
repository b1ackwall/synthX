package com.v1per.synthx.data.repository

import android.content.Context
import android.net.Uri
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.QueueDao
import com.v1per.synthx.data.db.SongArtistEntity
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongWithDetails
import com.v1per.synthx.data.db.SynthXDatabase
import androidx.room.withTransaction
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.ScanResult
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

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
) : SongRepository {

    // This class is a singleton (see companion.create), so this scope effectively lives as
    // long as the app process does — same shape as ExoPlayerEngine.engineScope /
    // QueuePersistence.scope at their own instance-lifetime level.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Shared across every getSongs() subscriber instead of re-querying and re-mapping the
    // full library (~1500 songs) on every SongsViewModel instance. Lazily rather than
    // WhileSubscribed: local song data changes rarely and Songs is a frequently-revisited
    // screen, so once the query has run once it's worth keeping warm for the rest of the
    // process — instant navigation matters more here than releasing this one cache early.
    // Room's Flow still auto-invalidates on real table changes, so this stays correct
    // across scans/edits regardless of how long the cache is held.
    private val songsFlow: StateFlow<List<Song>> =
        songDao.getAllSongsWithDetails().map { entries ->
            // One refs query per emission — 2 queries total regardless of library size
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
            entries.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getSongs(): Flow<List<Song>> = songsFlow

    // Same caching rationale as songsFlow above — shared across every getArtists()
    // subscriber (Artists list, Search) instead of re-querying per collector.
    private val artistsFlow: StateFlow<List<Artist>> =
        artistDao.getAllArtists().map { entities ->
            entities.map { Artist(id = it.id, name = it.name) }
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getArtists(): Flow<List<Artist>> = artistsFlow

    // Sort order (title) stays in the DAO query; the Flow conversion preserves it.
    override fun getSongsForArtist(artistId: String): Flow<List<Song>> =
        songDao.getSongsWithDetailsForArtist(artistId).map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    // Same caching rationale as songsFlow above — shared across every getAlbums()
    // subscriber (Albums list, Search) instead of re-querying per collector.
    private val albumsFlow: StateFlow<List<Album>> =
        albumDao.getAllAlbums().map { entries ->
            entries.map {
                Album(
                    id = it.id,
                    title = it.title,
                    artist = it.artistName,
                    albumArtUri = it.albumArtUri,
                )
            }
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getAlbums(): Flow<List<Album>> = albumsFlow

    // Sort order (track number, then title) stays in the DAO query.
    override fun getSongsForAlbum(albumId: String): Flow<List<Song>> =
        songDao.getSongsWithDetailsForAlbum(albumId).map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    override suspend fun scanLibrary(
        folderUri: Uri,
        onProgress: suspend (current: Int, total: Int) -> Unit,
    ): ScanResult = folderScanner.scan(folderUri, onProgress)

    override suspend fun verifyLibrary(): Int = folderScanner.verify()

    /**
     * Permanently removes a song. Its song_artists and playlist_songs rows go with it via
     * FK CASCADE — nothing here deletes them explicitly.
     *
     * The album-art re-point + row delete (via [deleteSongRowWithArtRepoint]) share one
     * transaction; the art file is unlinked only after it commits, since a file delete
     * cannot be rolled back alongside the DB. Same shared cleanup FolderScanner's internal
     * evictions use, so both deletion paths stay in sync.
     */
    override suspend fun deleteSong(id: String) = withContext(Dispatchers.IO) {
        // Backup-then-act: a failed backup (no folder configured) must not block the
        // delete the user explicitly asked for.
        backupManager.createBackup(BackupTier.Auto, BackupReason.DELETE_SONG)

        database.withTransaction {
            deleteSongRowWithArtRepoint(songDao, albumDao, id)
        }

        deleteSongArtFile(context, id)
        Unit
    }

    override fun getMissingSongs(): Flow<List<MissingSongInfo>> = songDao.getMissingSongs()

    override fun getMissingSongCount(): Flow<Int> = songDao.getMissingSongCount()

    override fun getAllGroups(): Flow<List<PlaylistGroup>> =
        playlistDao.getAllGroups().map { entities ->
            entities.map { PlaylistGroup(id = it.id, name = it.name) }
        }

    override suspend fun createGroup(name: String) {
        playlistDao.insertGroup(
            PlaylistGroupEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                position = 0,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun renameGroup(id: String, name: String) =
        playlistDao.renameGroup(id, name)

    override suspend fun deleteGroup(id: String) =
        playlistDao.deleteGroup(id)

    // Same caching rationale as songsFlow above — shared across every getAllPlaylists()
    // subscriber (SongActionsHost, Search) instead of re-querying per collector.
    private val allPlaylistsFlow: StateFlow<List<Playlist>> =
        playlistDao.getAllPlaylists().map { entities ->
            entities.map { it.toDomain() }
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getAllPlaylists(): Flow<List<Playlist>> = allPlaylistsFlow

    override fun getUngroupedPlaylists(): Flow<List<Playlist>> =
        playlistDao.getUngroupedPlaylists().map { entities ->
            entities.map { it.toDomain() }
        }

    override fun getPlaylistsForGroup(groupId: String): Flow<List<Playlist>> =
        playlistDao.getPlaylistsForGroup(groupId).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun createPlaylist(name: String, groupId: String?) {
        val now = System.currentTimeMillis()
        playlistDao.insertPlaylist(
            PlaylistEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                description = null,
                groupId = groupId,
                position = 0,
                createdAt = now,
                updatedAt = now,
                coverArtUri = null,
            )
        )
    }

    override suspend fun renamePlaylist(id: String, name: String) =
        playlistDao.renamePlaylist(id, name, System.currentTimeMillis())

    override suspend fun movePlaylistToGroup(id: String, groupId: String?) =
        playlistDao.movePlaylistToGroup(id, groupId, System.currentTimeMillis())

    // Hooked here rather than in the ViewModels because both PlaylistListViewModel and
    // GroupDetailViewModel delete playlists through this one call.
    override suspend fun deletePlaylist(id: String) {
        backupManager.createBackup(BackupTier.Auto, BackupReason.DELETE_PLAYLIST)
        playlistDao.deletePlaylist(id)
    }

    // Joins straight to playlist_songs (position order, is_missing filter) rather than
    // re-fetching/joining the whole library and filtering down in Kotlin.
    override fun getSongsForPlaylist(playlistId: String): Flow<List<Song>> =
        songDao.getSongsWithDetailsForPlaylist(playlistId).map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    override suspend fun getSongIdsForPlaylist(playlistId: String): List<String> =
        playlistDao.getSongIdsForPlaylist(playlistId)

    override suspend fun setPlaylistSongs(playlistId: String, songIds: List<String>) {
        playlistDao.replacePlaylistSongs(playlistId, songIds)
    }

    override suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean {
        // Checked rather than relying on REPLACE-on-conflict: that would silently move an
        // already-present song to the end, which reads as the playlist reordering itself.
        if (playlistDao.containsSong(playlistId, songId)) return false

        val nextPosition = (playlistDao.getMaxPosition(playlistId) ?: -1) + 1
        playlistDao.insertPlaylistSong(
            PlaylistSongEntity(
                playlistId = playlistId,
                songId = songId,
                position = nextPosition,
                addedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    override suspend fun removeSongFromPlaylist(playlistId: String, songId: String) =
        playlistDao.removePlaylistSong(playlistId, songId)

    override suspend fun getQueueSongs(): List<Song> = withContext(Dispatchers.IO) {
        val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
        queueDao.getQueueItems().map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    }

    override suspend fun getQueueSongIds(): List<String> = withContext(Dispatchers.IO) {
        queueDao.getQueueSongIds()
    }

    override suspend fun setQueueSongs(songIds: List<String>) = withContext(Dispatchers.IO) {
        queueDao.replaceQueue(songIds)
    }

    override suspend fun getBaselineQueueSongs(): List<Song> = withContext(Dispatchers.IO) {
        val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
        queueDao.getBaselineQueueItems().map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    }

    override suspend fun getBaselineQueueSongIds(): List<String> = withContext(Dispatchers.IO) {
        queueDao.getBaselineQueueSongIds()
    }

    override suspend fun setBaselineQueueSongs(songIds: List<String>) = withContext(Dispatchers.IO) {
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
                        ),
                        backupManager = BackupManager.create(appContext),
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}
