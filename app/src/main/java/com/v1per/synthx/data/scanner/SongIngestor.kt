package com.v1per.synthx.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.AlbumEntity
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.ArtistEntity
import com.v1per.synthx.data.db.SongArtistDao
import com.v1per.synthx.data.db.SongArtistEntity
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TAG = "SongIngestor"

// Comma is the ONLY artist-splitting delimiter, deliberately. Introducing other
// delimiters (&, "and", "feat.", ";", etc.) would incorrectly split legitimate
// single-act names that contain them — e.g. "Dimitri Vegas & Like Mike" and
// "Matisse & Sadko" are each one duo, not two artists. There is no reliable way
// to distinguish "two artists joined by &" from "one act whose name contains &"
// automatically. Comma-only accepts the narrower failure (a comma-containing
// single-artist name, e.g. "Earth, Wind & Fire," gets incorrectly split) in
// exchange for never incorrectly merging or splitting the much more common
// ampersand/feat./and-joined act names. A future manual per-artist review/merge
// feature is the intended way to close the remaining gap — not a smarter
// auto-splitter, which would just move the false-positive risk elsewhere.
private val ARTIST_DELIMITERS = listOf(",")

/**
 * Per-file metadata extraction and row writing for [FolderScanner]'s ingest loop.
 *
 * **Transaction contract:** every *writing* method here — [insertNewSong], [retagIfChanged],
 * [resolveAlbum], [resolveArtist] — is expected to be called from inside a
 * `database.withTransaction { ... }` block established by the caller. In production that is
 * [FolderScanner.scanAlreadyAdmitted], whose whole mutation phase runs in one transaction so
 * a cancelled or failed scan rolls back rather than leaving the library half-reconciled. Room
 * carries the active transaction in the coroutine context, so these calls join it implicitly;
 * this class opens no transaction of its own and must not be called outside one. When this
 * logic lived inline in [FolderScanner] that was self-evident from proximity — it is stated
 * explicitly here now that it is not.
 *
 * [extractMetadata] is the deliberate exception, and the reason the split exists: it touches
 * no database at all, so a scan runs it for every file up front, concurrently and outside the
 * transaction (see [extractScanData]), and then hands the results to the writing methods.
 * Calling it from inside the transaction is still *correct* — [insertNewSong] and
 * [retagIfChanged] fall back to exactly that when handed no pre-extracted data — but it
 * re-introduces the per-file blocking read the split exists to keep out of the transaction,
 * so it should stay a fallback for mis-predicted branches rather than a normal path.
 *
 * Admission is likewise the caller's responsibility: [FolderScanner]'s entrypoints already
 * hold RestoreCoordinator admission for the whole scan (see `FolderScannerAdmission`), so
 * nothing here re-enters the gate.
 */
internal class SongIngestor(
    private val context: Context,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val songArtistDao: SongArtistDao,
) {

    /**
     * Reads one file's tags — and, when [includeArt], its embedded cover — into memory.
     *
     * **Touches no database.** This is the entire file-in/data-out half of what
     * [insertNewSong] and [retagIfChanged] used to do inline, lifted out so a scan can run it
     * for every file concurrently before its write transaction opens. Nothing here depends on
     * a transaction, a dispatcher, or any state shared with another call: the
     * `MediaMetadataRetriever` is constructed, used and released entirely within this call,
     * and [AlbumArtExtractor] is stateless and writes only to `art_<songId>.*` paths.
     *
     * Field derivation is deliberately identical to what the two callers did before the split
     * — same filename title fallback, same 4-char year truncation, same `"5/12"` track
     * handling, same comma-only artist splitting — so a row written from this result is the
     * row the old inline path would have written.
     *
     * Never throws except for cancellation: an unreadable file degrades to
     * [ExtractedMetadata.readOk] `= false` with filename-derived fallbacks, and an
     * `OutOfMemoryError` from art decoding degrades to no art, exactly as before.
     *
     * [includeArt] must be `false` for the retag path. Decoding and rewriting a bitmap is
     * precisely the cost that path exists to avoid paying when nothing changed.
     */
    suspend fun extractMetadata(
        file: DocumentFile,
        songId: String,
        includeArt: Boolean,
    ): ExtractedMetadata {
        // The only cancellation check this call can offer: everything below is blocking
        // native work that never observes it. Cancelling therefore costs at most one file.
        currentCoroutineContext().ensureActive()

        val retriever = MediaMetadataRetriever()
        var readOk = false
        var rawTitle: String? = null
        var rawArtist: String? = null
        var rawAlbum: String? = null
        var rawDuration: String? = null
        var rawYear: String? = null
        var rawTrackNumber: String? = null
        var albumArtUri: String? = null
        var albumArtFullUri: String? = null
        try {
            retriever.setDataSource(context, file.uri)
            rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            rawTrackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            // Set before art, not after: the tags are what `readOk` speaks for. Art failing
            // is already expressed by a null URI and must not make a good tag read look bad.
            readOk = true
            if (includeArt) {
                val (compressedArtUri, fullArtUri) = AlbumArtExtractor.extract(context, retriever, songId)
                albumArtUri = compressedArtUri
                albumArtFullUri = fullArtUri
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable metadata — fall back to filename below, keep the song tracked
        } catch (e: OutOfMemoryError) {
            // Art extraction (or, in principle, any step above it) blew the memory budget —
            // this must not take the whole scan down. Degrade exactly like the Exception
            // path above: keep the song tracked, just without whatever wasn't extracted.
            Log.w(TAG, "OutOfMemoryError ingesting song $songId — continuing without full metadata/art", e)
        } finally {
            retriever.release()
        }

        val title = rawTitle?.takeIf { it.isNotBlank() }
            ?: file.name?.substringBeforeLast('.')
            ?: "Unknown"
        val year = rawYear?.trim()?.take(4)?.toIntOrNull()
        // Track metadata is often "5/12" (track 5 of 12) — keep only the track part
        val trackNumber = rawTrackNumber?.substringBefore("/")?.trim()?.toIntOrNull()

        // Parsed once, up front: album resolution needs the position-0 ("primary") artist to
        // match against — see AlbumDao.getAlbumByTitleAndArtist — and that has to be known
        // before the song row (and its song_artists rows) exist to join against. Both writing
        // paths reuse this same list rather than re-splitting.
        val artistNames = splitArtists(rawArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist")
            .distinctBy { it.lowercase() }
        val primaryArtistNormalized = (artistNames.firstOrNull() ?: "Unknown Artist")
            .trim()
            .lowercase()

        return ExtractedMetadata(
            readOk = readOk,
            title = title,
            artistNames = artistNames,
            primaryArtistNormalized = primaryArtistNormalized,
            albumTitle = rawAlbum?.takeIf { it.isNotBlank() }?.trim(),
            duration = rawDuration?.toLongOrNull() ?: 0L,
            year = year,
            trackNumber = trackNumber,
            albumArtUri = albumArtUri,
            albumArtFullUri = albumArtFullUri,
        )
    }

    /**
     * Writes the song, album and artist rows for a file the library has never seen.
     *
     * [extracted] is this file's already-read tags and art, from the scan's out-of-transaction
     * extraction phase. Passing `null` makes this read the file live instead — the pre-split
     * behaviour, kept as the safety net for a branch prediction that turned out wrong (see
     * [ScanBranch]). Everything after the read is unchanged and stays order-dependent: album
     * resolution matches against songs already inserted by earlier files in this same pass,
     * and whichever song first mints an album row donates its cover to it.
     */
    suspend fun insertNewSong(
        file: DocumentFile,
        id: String,
        path: String,
        isStarred: Boolean,
        extracted: ExtractedMetadata? = null,
    ): Song {
        val meta = extracted ?: extractMetadata(file, id, includeArt = true)

        val album = meta.albumTitle?.let { albumTitle ->
            resolveAlbum(albumTitle, meta.primaryArtistNormalized, meta.year, meta.albumArtUri)
        }
        val albumId = album?.id
        val dateAdded = System.currentTimeMillis()

        songDao.upsertSong(
            SongEntity(
                id = id,
                title = meta.title,
                albumId = albumId,
                duration = meta.duration,
                filePath = path,
                albumArtUri = meta.albumArtUri,
                albumArtFullUri = meta.albumArtFullUri,
                year = meta.year,
                trackNumber = meta.trackNumber,
                dateAdded = dateAdded,
                // Explicit, not the entity's constructor default — this song's starred
                // state is derived from which root it was found under, not "every new
                // song starts starred".
                isStarred = isStarred,
            )
        )

        songArtistDao.deleteSongArtists(id)
        val artists = meta.artistNames.mapIndexed { index, name ->
            val artist = resolveArtist(name)
            songArtistDao.insertSongArtist(
                SongArtistEntity(songId = id, artistId = artist.id, position = index)
            )
            Artist(id = artist.id, name = artist.name)
        }

        return Song(
            id = id,
            title = meta.title,
            artist = artists.joinToString(", ") { it.name }.ifEmpty { "Unknown Artist" },
            album = album?.title ?: "Unknown Album",
            duration = meta.duration,
            filePath = path,
            albumArtUri = meta.albumArtUri,
            albumArtFullUri = meta.albumArtFullUri,
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
     * [AlbumArtExtractor.extract] entirely: that decodes and rewrites a bitmap file, which
     * is the kind of per-file cost this path exists to avoid paying when nothing changed.
     *
     * [extracted] is this file's already-read tags, from the scan's out-of-transaction
     * extraction phase, and must have been read with `includeArt = false`. Passing `null`
     * makes this read the file live instead — the pre-split behaviour, kept as a fallback.
     * A read that failed ([ExtractedMetadata.readOk] `= false`) returns `false` without
     * touching the row, exactly as the inline version's `catch` did.
     */
    suspend fun retagIfChanged(
        file: DocumentFile,
        existing: SongEntity,
        extracted: ExtractedMetadata? = null,
    ): Boolean {
        val meta = extracted ?: extractMetadata(file, existing.id, includeArt = false)

        // Unreadable this pass — leave the stored row alone rather than falling back to
        // filename/"Unknown Artist" and clobbering tags that were fine a moment ago.
        if (!meta.readOk) return false

        // Indexed name lookups, same as a fresh scan resolves — inserts only happen if the
        // tag edit genuinely introduced a new artist/album, not on every unchanged file.
        val newArtistIds = meta.artistNames.map { resolveArtist(it).id }
        val albumId = meta.albumTitle?.let { albumTitle ->
            resolveAlbum(albumTitle, meta.primaryArtistNormalized, meta.year, existing.albumArtUri).id
        }

        val currentArtistIds = songDao.getSongArtistRefs(existing.id).map { it.artistId }
        val artistsChanged = currentArtistIds != newArtistIds
        val songChanged = existing.title != meta.title ||
            existing.year != meta.year ||
            existing.trackNumber != meta.trackNumber ||
            existing.albumId != albumId

        if (!songChanged && !artistsChanged) return false

        if (songChanged) {
            songDao.upsertSong(
                existing.copy(
                    title = meta.title,
                    year = meta.year,
                    trackNumber = meta.trackNumber,
                    albumId = albumId,
                )
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

    suspend fun resolveAlbum(
        title: String,
        primaryArtistNormalized: String,
        year: Int?,
        albumArtUri: String?,
    ): AlbumEntity = albumDao.getOrInsertAlbum(title, primaryArtistNormalized, year, albumArtUri)

    suspend fun resolveArtist(name: String): ArtistEntity {
        val normalized = name.trim().lowercase()
        return artistDao.getOrInsertArtist(name.trim(), normalized)
    }
}

private fun splitArtists(raw: String): List<String> {
    var parts = listOf(raw)
    for (delimiter in ARTIST_DELIMITERS) {
        parts = parts.flatMap { it.split(delimiter, ignoreCase = true) }
    }
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}
