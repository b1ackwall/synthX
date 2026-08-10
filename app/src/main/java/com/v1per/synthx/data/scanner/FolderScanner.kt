package com.v1per.synthx.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
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
import androidx.room.withTransaction
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanResult(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val unchanged: Int,
)

class FolderScanner(
    private val context: Context,
    private val database: SynthXDatabase,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val songArtistDao: SongArtistDao,
) {

    /**
     * Scans [folderUri] and reconciles the library against it.
     *
     * Concurrency is no longer guarded here — LibraryScanWorker enqueues under a unique
     * work name, so only one scan can be in flight per process.
     *
     * [onProgress] receives `(current, total)` where `current` is the number of audio
     * files fully processed so far. Two phases have no meaningful denominator — the
     * file-system walk before the file list exists, and the missing-detection plus
     * orphan-cleanup passes afterwards — and report [PROGRESS_INDETERMINATE] for **both**
     * arguments. Callers should treat `total <= 0` as "show a spinner, not a bar".
     *
     * The whole mutation phase runs in one Room transaction, so a cancelled or failed
     * scan rolls back rather than leaving the library half-reconciled. Cancellation is
     * checked once per file, so it takes effect within one file's work.
     */
    suspend fun scan(
        folderUri: Uri,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = withContext(Dispatchers.IO) {
        onProgress(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE)

        // Cleanup: delete old file:// art files from cacheDir. Art now lives in
        // filesDir/art/; these are orphans from before that move. No-op once drained.
        context.cacheDir
            .listFiles { file -> file.name.startsWith("art_") && file.name.endsWith(".png") }
            ?.forEach { it.delete() }

        val root = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext ScanResult(added = 0, updated = 0, missing = 0, unchanged = 0)

        // File-system walk stays outside the transaction — it is the slowest phase and
        // touches no DB state.
        val audioFiles = collectAudioFiles(root)
        val foundPaths = audioFiles.mapTo(mutableSetOf()) { it.uri.toString() }

        // Song ids evicted mid-transaction by the two paths below — their art files are
        // deleted only after the transaction commits (see the loop after withTransaction),
        // matching SongRepositoryImpl.deleteSong's commit-then-delete-file ordering.
        val pendingArtCleanup = mutableListOf<String>()

        val result = database.withTransaction {
            var added = 0
            var updated = 0
            var unchanged = 0

            for ((index, file) in audioFiles.withIndex()) {
                // Cooperative cancellation: the per-file work below is mostly blocking
                // I/O, so without this a cancelled scan would run to the end of the list.
                currentCoroutineContext().ensureActive()
                onProgress(index, audioFiles.size)

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
                                deleteSongRowWithArtRepoint(songDao, albumDao, occupant.id)
                                pendingArtCleanup.add(occupant.id)
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
                songDao.getSongByPath(path)?.let { stale ->
                    deleteSongRowWithArtRepoint(songDao, albumDao, stale.id)
                    pendingArtCleanup.add(stale.id)
                }

                insertNewSong(file, id, path)
                added++
            }

            // No per-file denominator from here on — back to indeterminate.
            onProgress(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE)

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

            // Clean up artist and album rows that no longer have any songs referencing them
            artistDao.deleteOrphanArtists()
            albumDao.deleteOrphanAlbums()

            ScanResult(
                added = added,
                updated = updated,
                missing = missing,
                unchanged = unchanged,
            )
        }

        // Only after the transaction commits — a file delete cannot be rolled back
        // alongside the DB the way the row deletes above can.
        pendingArtCleanup.forEach { deleteSongArtFile(context, it) }

        result
    }

    /**
     * Cheap existence sweep over the whole library: flags songs whose stored `file_path`
     * no longer resolves. No hashing, no metadata, no folder walk — one `exists()` probe
     * per row, committed row by row rather than in a transaction, so it can run on launch
     * without blocking anything.
     *
     * Deliberately one-way. A song that resolves is left exactly as it is, including one
     * already flagged missing — un-flagging is [scan]'s job, since only a real scan can
     * confirm the file's content still matches the row's identity hash.
     *
     * @return the number of songs newly flagged missing by this pass.
     */
    suspend fun verify(): Int = withContext(Dispatchers.IO) {
        var newlyMissing = 0
        for (song in songDao.getAllSongsOnce()) {
            currentCoroutineContext().ensureActive()
            if (song.isMissing) continue // already flagged — nothing this pass can add

            val exists = try {
                DocumentFile.fromSingleUri(context, Uri.parse(song.filePath))?.exists() == true
            } catch (e: Exception) {
                // Unparseable or revoked URI — treat as gone, same as a failed probe
                false
            }

            if (!exists) {
                songDao.markMissing(song.id, System.currentTimeMillis())
                newlyMissing++
            }
        }
        newlyMissing
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

        // Parsed once, up front: album resolution now needs the position-0 ("primary")
        // artist to match against — see AlbumDao.getAlbumByTitleAndArtist — and that has
        // to be known before the song row (and its song_artists rows) exist to join
        // against. The per-artist loop below reuses this same list.
        val rawArtistNames = splitArtists(rawArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist")
            .distinctBy { it.lowercase() }
        val primaryArtistNormalized = (rawArtistNames.firstOrNull() ?: "Unknown Artist")
            .trim()
            .lowercase()

        val albumId = rawAlbum?.takeIf { it.isNotBlank() }?.trim()?.let { albumTitle ->
            resolveAlbum(albumTitle, primaryArtistNormalized, year, albumArtUri).id
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
        rawArtistNames.forEachIndexed { index, name ->
            val artist = resolveArtist(name)
            songArtistDao.insertSongArtist(
                SongArtistEntity(songId = id, artistId = artist.id, position = index)
            )
        }
    }

    private suspend fun resolveAlbum(
        title: String,
        primaryArtistNormalized: String,
        year: Int?,
        albumArtUri: String?,
    ): AlbumEntity = albumDao.getOrInsertAlbum(title, primaryArtistNormalized, year, albumArtUri)

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

            // Store in filesDir/art/ — permanent, survives cache clears
            val artDir = File(context.filesDir, "art").also { it.mkdirs() }
            val artFile = File(artDir, "art_$songId.png")

            FileOutputStream(artFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bitmap.recycle()

            // Return content:// URI via FileProvider — safe across process boundaries
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                artFile,
            ).toString()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Sentinel for both `onProgress` arguments while no file count is known yet. */
        const val PROGRESS_INDETERMINATE = -1

        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg")

        private val ARTIST_DELIMITERS = listOf(",")
    }
}
