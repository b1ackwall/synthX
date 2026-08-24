package com.v1per.synthx.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
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
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.Song
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanResult(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val unchanged: Int,
    /**
     * Whether the missing-detection pass actually ran. `false` means `is_missing` was left
     * entirely untouched this scan — either it was not requested, or a configured root could
     * not be enumerated and reconciling against a partial path set would have wrongly flagged
     * songs. Distinguishes that from a pass that ran and legitimately found [missing] == 0.
     */
    val missingReconciled: Boolean = true,
)

class FolderScanner(
    private val context: Context,
    private val database: SynthXDatabase,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val songArtistDao: SongArtistDao,
    private val playlistExportManager: PlaylistExportManager,
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
     *
     * [reconcileMissing] controls the post-loop missing-detection pass, which compares
     * *every* song in the library against the set of files found on disk. Pass `false` when
     * the caller can't account for the whole library — scanning the new-arrivals tree, say —
     * so `is_missing` is left completely untouched and [ScanResult.missing] reports 0.
     *
     * [otherRootUris] are the *other* configured audio roots, i.e. every root except
     * [folderUri] itself. They are enumerated (paths only — no hashing, no metadata, no
     * ingestion) purely so their files join the found-path set that missing-detection
     * reconciles against. Without them a song living only under another root would be flagged
     * missing by every scan of this one. Ignored entirely when [reconcileMissing] is `false`.
     * This parameter is plain data: the scanner deliberately knows nothing about preferences,
     * so the caller decides what the roots are.
     *
     * If any root in [otherRootUris] can't be enumerated — grant revoked, provider error,
     * unresolvable URI — missing-detection is skipped for the whole scan rather than run
     * against a short path set that would wrongly flag every song under that root. The scan
     * still succeeds; [ScanResult.missingReconciled] reports `false`.
     */
    suspend fun scan(
        folderUri: Uri,
        reconcileMissing: Boolean = true,
        otherRootUris: List<Uri> = emptyList(),
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = withContext(Dispatchers.IO) {
        val scanStartMs = System.currentTimeMillis()
        onProgress(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE)

        // Cleanup: delete old file:// art files from cacheDir. Art now lives in
        // filesDir/art/; these are orphans from before that move. No-op once drained.
        context.cacheDir
            .listFiles { file -> file.name.startsWith("art_") && file.name.endsWith(".png") }
            ?.forEach { it.delete() }

        val root = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext ScanResult(
                added = 0,
                updated = 0,
                missing = 0,
                unchanged = 0,
                missingReconciled = false,
            )

        // File-system walk stays outside the transaction — it is the slowest phase and
        // touches no DB state.
        val walkStartMs = System.currentTimeMillis()
        val audioFiles = collectAudioFiles(root)
        val foundPaths = audioFiles.mapTo(mutableSetOf()) { it.uri.toString() }

        // Secondary roots, also before the transaction opens: each is hundreds of binder
        // round-trips, and holding a Room transaction across them would block every other
        // writer for the duration. Enumeration only — these files are not ingested here.
        val reconcile = if (reconcileMissing) {
            collectOtherRootPaths(otherRootUris, into = foundPaths)
        } else {
            false
        }
        Log.d(
            TAG,
            "Scan walk complete in ${System.currentTimeMillis() - walkStartMs}ms — " +
                "${audioFiles.size} files found under $folderUri",
        )

        // Song ids evicted mid-transaction by the two paths below — their art files are
        // deleted only after the transaction commits (see the loop after withTransaction),
        // matching SongRepositoryImpl.deleteSong's commit-then-delete-file ordering.
        val pendingArtCleanup = mutableListOf<String>()

        var retagged = 0
        val txnStartMs = System.currentTimeMillis()
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
                        // Same path, same hash — the byte ranges the id is built from
                        // (first/last 64KB + size) didn't move, but an ID3 edit that lands
                        // outside those ranges (or reuses the same padding) wouldn't touch
                        // them either. A cheap tag re-read catches that case without paying
                        // for a full metadata extraction + resolution on every untouched file.
                        else -> if (retagIfChanged(file, existing)) {
                            retagged++
                            updated++
                        } else {
                            unchanged++
                        }
                    }
                    continue
                }

                // Same path, different hash: the file content changed — treat as new.
                // The replaced song's id is gone, so any playlist_songs rows referencing
                // it cascade-delete silently (no SongRepositoryImpl mutation method runs
                // here to trigger the usual scheduleExport() call) — request one explicitly
                // so the exported JSON doesn't keep citing a deleted song id.
                val staleId = songDao.getSongByPath(path)?.let { stale ->
                    deleteSongRowWithArtRepoint(songDao, albumDao, stale.id)
                    pendingArtCleanup.add(stale.id)
                    playlistExportManager.scheduleExport()
                    stale.id
                }

                val newSong = insertNewSong(file, id, path)
                // If the replaced song is the one currently loaded in the player, its
                // in-memory Song (title/art) would otherwise go stale — see SongRemapEvents.
                if (staleId != null) {
                    SongRemapEvents.notifyRemap(staleId, newSong)
                }
                added++
            }

            // No per-file denominator from here on — back to indeterminate.
            onProgress(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE)

            var missing = 0
            if (reconcile) {
                val now = System.currentTimeMillis()
                for (song in songDao.getAllSongsOnce()) {
                    if (song.filePath !in foundPaths) {
                        if (!song.isMissing) {
                            songDao.markMissing(song.id, now)
                        }
                        missing++
                    }
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
                missingReconciled = reconcile,
            )
        }
        Log.d(
            TAG,
            "Scan transaction complete in ${System.currentTimeMillis() - txnStartMs}ms — " +
                "added=${result.added} updated=${result.updated} (retagged=$retagged) " +
                "unchanged=${result.unchanged} missing=${result.missing}",
        )

        // Only after the transaction commits — a file delete cannot be rolled back
        // alongside the DB the way the row deletes above can.
        pendingArtCleanup.forEach { deleteSongArtFile(context, it) }

        Log.d(TAG, "Scan complete in ${System.currentTimeMillis() - scanStartMs}ms for $folderUri")
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

    /**
     * Enumerates every [otherRootUri][otherRootUris] into [into], and returns whether
     * missing-detection may proceed.
     *
     * Returns `false` — abort reconciliation — the moment any root can't be enumerated,
     * without partially populating a decision the caller would then act on. Reachability is
     * probed explicitly rather than inferred from the walk, because DocumentFile swallows
     * every provider error: `listFiles()` catches its query exception and returns an empty
     * array, and the metadata accessors return their default on failure. A revoked grant is
     * therefore indistinguishable from an empty folder at the walk level, and reconciling
     * against that silence is exactly how every song under the root gets flagged missing.
     */
    private suspend fun collectOtherRootPaths(
        otherRootUris: List<Uri>,
        into: MutableSet<String>,
    ): Boolean {
        for (uri in otherRootUris) {
            currentCoroutineContext().ensureActive()

            // The definitive revocation signal — a stored URI outlives its grant.
            val hasReadGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (!hasReadGrant) {
                Log.w(TAG, "Skipping missing-detection: no read grant for configured root $uri")
                return false
            }

            val otherRoot = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            // isDirectory is a real query, so it also catches a grant that exists but no
            // longer resolves (deleted folder, ejected volume, provider error).
            if (otherRoot == null || !otherRoot.isDirectory) {
                Log.w(TAG, "Skipping missing-detection: configured root is unreachable — $uri")
                return false
            }

            try {
                collectAudioFiles(otherRoot).mapTo(into) { it.uri.toString() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Skipping missing-detection: failed to walk configured root $uri", e)
                return false
            }
        }
        return true
    }

    /**
     * Pure enumeration — no hashing, no metadata, no DB access. Suspending only for the
     * cancellation check: this now also runs against secondary roots before the transaction
     * opens, where the per-file `ensureActive` in the ingest loop offers no cover, and a
     * deep tree is thousands of binder round-trips with nothing else to interrupt it.
     */
    private suspend fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
        currentCoroutineContext().ensureActive()
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

    private suspend fun insertNewSong(file: DocumentFile, id: String, path: String): Song {
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

        val album = rawAlbum?.takeIf { it.isNotBlank() }?.trim()?.let { albumTitle ->
            resolveAlbum(albumTitle, primaryArtistNormalized, year, albumArtUri)
        }
        val albumId = album?.id
        val dateAdded = System.currentTimeMillis()

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
                dateAdded = dateAdded,
            )
        )

        songArtistDao.deleteSongArtists(id)
        val artists = rawArtistNames.mapIndexed { index, name ->
            val artist = resolveArtist(name)
            songArtistDao.insertSongArtist(
                SongArtistEntity(songId = id, artistId = artist.id, position = index)
            )
            Artist(id = artist.id, name = artist.name)
        }

        return Song(
            id = id,
            title = title,
            artist = artists.joinToString(", ") { it.name }.ifEmpty { "Unknown Artist" },
            album = album?.title ?: "Unknown Album",
            duration = rawDuration?.toLongOrNull() ?: 0L,
            filePath = path,
            albumArtUri = albumArtUri,
            isMissing = false,
            dateAdded = dateAdded,
            albumId = albumId,
            artists = artists,
        )
    }

    /**
     * The "unchanged" fast path's tag-drift check — same file identity (path + hash), but
     * ID3 tags can be edited without touching the byte ranges [HashUtils] hashes, so the id
     * alone can't prove the tags are still what's stored. Re-reads just the text tags (no
     * embedded-art decode, no waveform/duration re-read) and only writes back — song row,
     * and song_artists if the artist list changed — when something actually differs from
     * what's stored. Returns whether it wrote anything, so the caller can count this file as
     * updated rather than unchanged.
     *
     * Deliberately mirrors [insertNewSong]'s field derivation (same title/year/track
     * fallbacks, same artist splitting, same album resolution) so a retag lands on exactly
     * the row a fresh scan of the same file would have produced — but skips
     * [extractAlbumArt] entirely: that decodes and rewrites a bitmap file, which is the kind
     * of per-file cost this path exists to avoid paying when nothing changed.
     */
    private suspend fun retagIfChanged(file: DocumentFile, existing: SongEntity): Boolean {
        val retriever = MediaMetadataRetriever()
        val rawTitle: String?
        val rawArtist: String?
        val rawAlbum: String?
        val rawYear: String?
        val rawTrackNumber: String?
        try {
            retriever.setDataSource(context, file.uri)
            rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            rawTrackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
        } catch (e: Exception) {
            // Unreadable this pass — leave the stored row alone rather than falling back to
            // filename/"Unknown Artist" and clobbering tags that were fine a moment ago.
            return false
        } finally {
            retriever.release()
        }

        val title = rawTitle?.takeIf { it.isNotBlank() }
            ?: file.name?.substringBeforeLast('.')
            ?: "Unknown"
        val year = rawYear?.trim()?.take(4)?.toIntOrNull()
        val trackNumber = rawTrackNumber?.substringBefore("/")?.trim()?.toIntOrNull()

        val rawArtistNames = splitArtists(rawArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist")
            .distinctBy { it.lowercase() }
        val primaryArtistNormalized = (rawArtistNames.firstOrNull() ?: "Unknown Artist")
            .trim()
            .lowercase()

        // Indexed name lookups, same as a fresh scan resolves — inserts only happen if the
        // tag edit genuinely introduced a new artist/album, not on every unchanged file.
        val newArtistIds = rawArtistNames.map { resolveArtist(it).id }
        val albumId = rawAlbum?.takeIf { it.isNotBlank() }?.trim()?.let { albumTitle ->
            resolveAlbum(albumTitle, primaryArtistNormalized, year, existing.albumArtUri).id
        }

        val currentArtistIds = songDao.getSongArtistRefs(existing.id).map { it.artistId }
        val artistsChanged = currentArtistIds != newArtistIds
        val songChanged = existing.title != title ||
            existing.year != year ||
            existing.trackNumber != trackNumber ||
            existing.albumId != albumId

        if (!songChanged && !artistsChanged) return false

        if (songChanged) {
            songDao.upsertSong(
                existing.copy(title = title, year = year, trackNumber = trackNumber, albumId = albumId)
            )
        }
        if (artistsChanged) {
            songArtistDao.deleteSongArtists(existing.id)
            newArtistIds.forEachIndexed { index, artistId ->
                songArtistDao.insertSongArtist(
                    SongArtistEntity(songId = existing.id, artistId = artistId, position = index)
                )
            }
        }
        return true
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
            val decoded = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) ?: return null

            // Downscale only — never upscale art that's already smaller than the cap.
            val longestSide = maxOf(decoded.width, decoded.height)
            val bitmap = if (longestSide > MAX_ART_DIMENSION_PX) {
                val scale = MAX_ART_DIMENSION_PX.toFloat() / longestSide
                val scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                decoded.recycle()
                scaled
            } else {
                decoded
            }

            // Store in filesDir/art/ — permanent, survives cache clears. JPEG at quality 90 —
            // lossless PNG at 100 was often larger than the embedded source for photographic
            // covers; 90 is visually indistinguishable at the sizes this art is ever displayed
            // at (up to full player-sheet width) while still compressing meaningfully smaller.
            val artDir = File(context.filesDir, "art").also { it.mkdirs() }
            val artFile = File(artDir, "art_$songId.jpg")

            FileOutputStream(artFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
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
        private const val TAG = "SynthXScan"

        /** Longest edge cap for stored album art — well above any on-screen display size. */
        private const val MAX_ART_DIMENSION_PX = 1000

        /** Sentinel for both `onProgress` arguments while no file count is known yet. */
        const val PROGRESS_INDETERMINATE = -1

        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg")

        private val ARTIST_DELIMITERS = listOf(",")
    }
}
