package com.v1per.synthx.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.AlbumEntity
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.ArtistEntity
import com.v1per.synthx.data.db.SongArtistDao
import com.v1per.synthx.data.db.SongArtistEntity
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScanResult(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val unchanged: Int,
)

class FolderScanner(
    private val context: Context,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val songArtistDao: SongArtistDao,
) {

    // Serialized process-wide: a launch scan and a Settings rescan can otherwise
    // interleave their read-then-write pairs and corrupt the library.
    suspend fun scan(folderUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        SynthXDatabase.scanMutex.withLock {
            val root = DocumentFile.fromTreeUri(context, folderUri)
                ?: return@withLock ScanResult(added = 0, updated = 0, missing = 0, unchanged = 0)

            val audioFiles = collectAudioFiles(root)
            val foundPaths = audioFiles.mapTo(mutableSetOf()) { it.uri.toString() }

            var added = 0
            var updated = 0
            var unchanged = 0

            for (file in audioFiles) {
                val path = file.uri.toString()
                // Unreadable file: no identity, nothing we can safely store
                val id = HashUtils.computeSongId(file, context) ?: continue

                val existing = songDao.getSongById(id)
                if (existing != null) {
                    when {
                        existing.filePath != path -> {
                            // Check if another song already occupies this path — remove it
                            // first to avoid UNIQUE constraint violation on file_path index
                            val occupant = songDao.getSongByPath(path)
                            if (occupant != null && occupant.id != existing.id) {
                                songDao.deleteSongById(occupant.id)
                            }
                            songDao.upsertSong(
                                existing.copy(
                                    filePath = path,
                                    isMissing = false,
                                    missingSince = null,
                                )
                            )
                            updated++
                        }
                        existing.isMissing -> {
                            // Same path, previously flagged missing — the file is back
                            songDao.markFound(id)
                            updated++
                        }
                        else -> unchanged++
                    }
                    continue
                }

                // Same path, different hash: the file content changed — treat as new
                songDao.getSongByPath(path)?.let { stale -> songDao.deleteSongById(stale.id) }

                insertNewSong(file, id, path)
                added++
            }

            var missing = 0
            val now = System.currentTimeMillis()
            for (song in songDao.getAllSongsOnce()) {
                if (song.filePath !in foundPaths) {
                    if (!song.isMissing) {
                        songDao.markMissing(song.id, now)
                    }
                    missing++
                }
            }

            ScanResult(added = added, updated = updated, missing = missing, unchanged = unchanged)
        }
    }

    private fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> result.addAll(collectAudioFiles(child))
                child.name?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_EXTENSIONS ->
                    result.add(child)
            }
        }
        return result
    }

    private suspend fun insertNewSong(file: DocumentFile, id: String, path: String) {
        val retriever = MediaMetadataRetriever()
        var rawTitle: String? = null
        var rawArtist: String? = null
        var rawAlbum: String? = null
        var rawDuration: String? = null
        var rawYear: String? = null
        var rawTrackNumber: String? = null
        var albumArtUri: String? = null
        try {
            retriever.setDataSource(context, file.uri)
            rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            rawTrackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            albumArtUri = extractAlbumArt(retriever, id)
        } catch (e: Exception) {
            // Unreadable metadata — fall back to filename below, keep the song tracked
        } finally {
            retriever.release()
        }

        val title = rawTitle?.takeIf { it.isNotBlank() }
            ?: file.name?.substringBeforeLast('.')
            ?: "Unknown"
        val year = rawYear?.trim()?.take(4)?.toIntOrNull()
        // Track metadata is often "5/12" (track 5 of 12) — keep only the track part
        val trackNumber = rawTrackNumber?.substringBefore("/")?.trim()?.toIntOrNull()

        val albumId = rawAlbum?.takeIf { it.isNotBlank() }?.trim()?.let { albumTitle ->
            resolveAlbum(albumTitle, year, albumArtUri).id
        }

        songDao.upsertSong(
            SongEntity(
                id = id,
                title = title,
                albumId = albumId,
                duration = rawDuration?.toLongOrNull() ?: 0L,
                filePath = path,
                albumArtUri = albumArtUri,
                year = year,
                trackNumber = trackNumber,
                dateAdded = System.currentTimeMillis(),
            )
        )

        songArtistDao.deleteSongArtists(id)
        val rawArtistNames = splitArtists(rawArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist")
        rawArtistNames
            .distinctBy { it.lowercase() }
            .forEachIndexed { index, name ->
                val artist = resolveArtist(name)
                songArtistDao.insertSongArtist(
                    SongArtistEntity(songId = id, artistId = artist.id, position = index)
                )
            }
    }

    private suspend fun resolveAlbum(title: String, year: Int?, albumArtUri: String?): AlbumEntity =
        albumDao.getOrInsertAlbum(title, year, albumArtUri)

    private suspend fun resolveArtist(name: String): ArtistEntity {
        val normalized = name.trim().lowercase()
        return artistDao.getOrInsertArtist(name.trim(), normalized)
    }

    private fun splitArtists(raw: String): List<String> {
        var parts = listOf(raw)
        for (delimiter in ARTIST_DELIMITERS) {
            parts = parts.flatMap { it.split(delimiter, ignoreCase = true) }
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun extractAlbumArt(retriever: MediaMetadataRetriever, songId: String): String? {
        return try {
            val artBytes = retriever.embeddedPicture ?: return null
            val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) ?: return null
            val artFile = File(context.cacheDir, "art_$songId.png")
            FileOutputStream(artFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            Uri.fromFile(artFile).toString()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg")

        private val ARTIST_DELIMITERS =
            listOf(" feat. ", " ft. ", " featuring ", " & ", " and ", ",")
    }
}
