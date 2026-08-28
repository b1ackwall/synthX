package com.v1per.synthx.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
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

private val ARTIST_DELIMITERS = listOf(",")

/**
 * Per-file metadata extraction and row writing for [FolderScanner]'s ingest loop.
 *
 * **Transaction contract:** every method here writes to Room and is expected to be called
 * from inside a `database.withTransaction { ... }` block established by the caller — in
 * production that is [FolderScanner.scanAlreadyAdmitted], whose whole mutation phase runs
 * in one transaction so a cancelled or failed scan rolls back rather than leaving the
 * library half-reconciled. Room carries the active transaction in the coroutine context,
 * so these calls join it implicitly; this class opens no transaction of its own and must
 * not be called outside one. When this logic lived inline in [FolderScanner] that was
 * self-evident from proximity — it is stated explicitly here now that it is not.
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

    suspend fun insertNewSong(file: DocumentFile, id: String, path: String): Song {
        val retriever = MediaMetadataRetriever()
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
            val (compressedArtUri, fullArtUri) = AlbumArtExtractor.extract(context, retriever, id)
            albumArtUri = compressedArtUri
            albumArtFullUri = fullArtUri
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
                albumArtFullUri = albumArtFullUri,
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
            albumArtFullUri = albumArtFullUri,
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
     */
    suspend fun retagIfChanged(file: DocumentFile, existing: SongEntity): Boolean {
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
