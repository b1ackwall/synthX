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
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

    suspend fun scan(folderUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext ScanResult(added = 0, updated = 0, missing = 0, unchanged = 0)

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
                        songDao.upsertSong(
                            existing.copy(filePath = path, isMissing = false, missingSince = null)
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

            // Same path, different hash: the file content changed — treat as a new song
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
        var albumArtUri: String? = null
        try {
            retriever.setDataSource(context, file.uri)
            rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
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

    private suspend fun resolveAlbum(title: String, year: Int?, albumArtUri: String?): AlbumEntity {
        albumDao.getAlbumByTitle(title)?.let { return it }
        val album = AlbumEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            year = year,
            albumArtUri = albumArtUri,
        )
        albumDao.upsertAlbum(album)
        return album
    }

    private suspend fun resolveArtist(name: String): ArtistEntity {
        val normalized = name.trim().lowercase()
        artistDao.getArtistByNormalizedName(normalized)?.let { return it }
        val artist = ArtistEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            nameNormalized = normalized,
        )
        artistDao.upsertArtist(artist)
        return artist
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
