package com.v1per.synthx.data.repository

import android.content.Context
import android.net.Uri
import com.v1per.synthx.data.db.SongArtistEntity
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongWithDetails
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.ScanResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SongRepositoryImpl(
    private val songDao: SongDao,
    private val folderScanner: FolderScanner,
) : SongRepository {

    override fun getSongs(): Flow<List<Song>> =
        songDao.getAllSongsWithDetails().map { entries ->
            // One refs query per emission — 2 queries total regardless of library size
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
            entries.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }

    override suspend fun scanLibrary(folderUri: Uri): ScanResult =
        folderScanner.scan(folderUri)

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
        )
    }

    companion object {
        // Manual DI entry point — everything hangs off the SynthXDatabase singleton
        fun create(context: Context): SongRepositoryImpl {
            val appContext = context.applicationContext
            val db = SynthXDatabase.getInstance(appContext)
            return SongRepositoryImpl(
                songDao = db.songDao(),
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
