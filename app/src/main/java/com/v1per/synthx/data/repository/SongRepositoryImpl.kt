package com.v1per.synthx.data.repository

import android.content.Context
import android.net.Uri
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.ScanResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SongRepositoryImpl(
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val folderScanner: FolderScanner,
) : SongRepository {

    override fun getSongs(): Flow<List<Song>> =
        songDao.getAllSongs().map { entities -> entities.map { it.toDomain() } }

    override suspend fun scanLibrary(folderUri: Uri): ScanResult =
        folderScanner.scan(folderUri)

    private suspend fun SongEntity.toDomain(): Song {
        val artists = artistDao.getArtistsForSong(id)
        val artist = if (artists.isEmpty()) {
            "Unknown Artist"
        } else {
            artists.joinToString(", ") { it.name }
        }
        val album = albumId?.let { albumDao.getAlbumById(it)?.title } ?: "Unknown Album"
        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            filePath = filePath,
            albumArtUri = albumArtUri,
        )
    }

    companion object {
        // Manual DI entry point — everything hangs off the SynthXDatabase singleton
        fun create(context: Context): SongRepositoryImpl {
            val appContext = context.applicationContext
            val db = SynthXDatabase.getInstance(appContext)
            return SongRepositoryImpl(
                songDao = db.songDao(),
                artistDao = db.artistDao(),
                albumDao = db.albumDao(),
                folderScanner = FolderScanner(
                    context = appContext,
                    songDao = db.songDao(),
                    artistDao = db.artistDao(),
                    albumDao = db.albumDao(),
                    songArtistDao = db.songArtistDao(),
                ),
            )
        }
    }
}
