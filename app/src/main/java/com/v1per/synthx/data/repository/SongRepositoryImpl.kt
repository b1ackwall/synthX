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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    override fun getSongs(): Flow<List<Song>> =
        songDao.getAllSongsWithDetails().map { entries ->
            // One refs query per emission — 2 queries total regardless of library size
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
            entries.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }

    override fun getArtists(): Flow<List<Artist>> =
        artistDao.getAllArtists().map { entities ->
            entities.map { Artist(id = it.id, name = it.name) }
        }

    // Sort order (title) stays in the DAO query; the Flow conversion preserves it.
    override fun getSongsForArtist(artistId: String): Flow<List<Song>> =
        songDao.getSongsWithDetailsForArtist(artistId).map { details ->
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    override fun getAlbums(): Flow<List<Album>> =
        albumDao.getAllAlbums().map { entries ->
            entries.map {
                Album(
                    id = it.id,
                    title = it.title,
                    artist = it.artistName,
                    albumArtUri = it.albumArtUri,
                )
            }
        }

    // Sort order (track number, then title) stays in the DAO query.
    override fun getSongsForAlbum(albumId: String): Flow<List<Song>> =
        songDao.getSongsWithDetailsForAlbum(albumId).map { details ->
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
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

    override fun getAllPlaylists(): Flow<List<Playlist>> =
        playlistDao.getAllPlaylists().map { entities ->
            entities.map { it.toDomain() }
        }

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

    // The playlist query supplies membership, `position` order, and the is_missing
    // filter; getAllSongsWithDetails supplies artists + album in one batched read.
    // 3 queries per emission regardless of playlist size, replacing 2 per song.
    override fun getSongsForPlaylist(playlistId: String): Flow<List<Song>> =
        combine(
            playlistDao.getSongsForPlaylist(playlistId),
            songDao.getAllSongsWithDetails(),
        ) { playlistEntities, allDetails ->
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
            val detailsById = allDetails.associateBy { it.song.id }
            playlistEntities.mapNotNull { entity ->
                detailsById[entity.id]?.toDomain(refsBySongId[entity.id] ?: emptyList())
            }
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
        // Manual DI entry point — everything hangs off the SynthXDatabase singleton
        fun create(context: Context): SongRepositoryImpl {
            val appContext = context.applicationContext
            val db = SynthXDatabase.getInstance(appContext)
            return SongRepositoryImpl(
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
            )
        }
    }
}
