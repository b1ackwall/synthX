package com.v1per.synthx.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FolderScanner(private val context: Context) {

    suspend fun scanFolder(folderUri: Uri): List<Song> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        collectAudioFiles(dir).mapNotNull { extractSong(it) }
    }

    private fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> result.addAll(collectAudioFiles(child))
                child.type in SUPPORTED_MIME_TYPES -> result.add(child)
            }
        }
        return result
    }

    private fun extractSong(file: DocumentFile): Song? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)

            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

            val filePath = file.uri.toString()
            val id = filePath.hashCode().toString()

            val title = rawTitle?.takeIf { it.isNotBlank() }
                ?: file.name?.substringBeforeLast('.')
                ?: "Unknown"
            val artist = rawArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val album = rawAlbum?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            val duration = rawDuration?.toLongOrNull() ?: 0L

            val albumArtUri = extractAlbumArt(retriever, id)

            Song(
                id = id,
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                filePath = filePath,
                albumArtUri = albumArtUri,
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
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
        private val SUPPORTED_MIME_TYPES = setOf(
            "audio/mpeg",
            "audio/flac",
            "audio/aac",
            "audio/mp4",
            "audio/x-wav",
            "audio/ogg",
        )
    }
}
