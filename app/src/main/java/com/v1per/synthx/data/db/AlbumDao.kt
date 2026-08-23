package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    @Upsert
    suspend fun upsertAlbum(album: AlbumEntity)

    /**
     * Matches on (title, primary artist) rather than title alone, so two different albums
     * that happen to share a title (different artists) no longer collapse into one row.
     * "Primary artist" is resolved by join against an *existing* song already in the
     * candidate album, using the same position-0 convention [getAllAlbums] and
     * `SongDao.getMissingSongs` already use elsewhere — not a stored column on albums, since
     * that would need backfilling for every album merged under the old title-only match.
     * [artistNormalized] must already be lowercase/trimmed the same way
     * `ArtistDao.getOrInsertArtist`'s callers normalize, so it lines up with
     * `artists.name_normalized`.
     */
    @Query(
        """
        SELECT albums.* FROM albums
        WHERE albums.title = :title COLLATE NOCASE
        AND EXISTS (
            SELECT 1 FROM songs
            INNER JOIN song_artists ON song_artists.song_id = songs.id
                AND song_artists.position = 0
            INNER JOIN artists ON artists.id = song_artists.artist_id
            WHERE songs.album_id = albums.id AND artists.name_normalized = :artistNormalized
        )
        LIMIT 1
        """
    )
    suspend fun getAlbumByTitleAndArtist(title: String, artistNormalized: String): AlbumEntity?

    /**
     * Look up an album by (title, primary artist), inserting it if absent. Atomic so two
     * concurrent scans cannot both miss the lookup and mint duplicate rows — `title` is
     * indexed but not unique, so SQLite would accept both.
     */
    @Transaction
    suspend fun getOrInsertAlbum(
        title: String,
        artistNormalized: String,
        year: Int?,
        albumArtUri: String?,
    ): AlbumEntity {
        getAlbumByTitleAndArtist(title, artistNormalized)?.let { return it }
        val album = AlbumEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            year = year,
            albumArtUri = albumArtUri,
        )
        upsertAlbum(album)
        return album
    }

    // Primary artist = position-0 artist of the album's first song (lowest track
    // number, falling back to lowest rowid for untagged files). Room's SQL parser
    // rejects window functions and NULLS LAST, so this uses a correlated subquery
    // and the "expr IS NULL" ordering trick instead.
    //
    // Albums where every song is_missing = 1 (or that have zero songs at all) are
    // excluded via the EXISTS clause below, so they don't linger as dead entries —
    // mirroring how missing songs already don't show up as tappable rows elsewhere.
    @Query(
        """
        SELECT
            albums.id,
            albums.title,
            albums.album_art_uri,
            COALESCE(artists.name, 'Unknown Artist') AS artistName
        FROM albums
        LEFT JOIN artists ON artists.id = (
            SELECT song_artists.artist_id
            FROM songs
            INNER JOIN song_artists ON song_artists.song_id = songs.id
                AND song_artists.position = 0
            WHERE songs.album_id = albums.id AND songs.is_missing = 0
            ORDER BY songs.track_number IS NULL, songs.track_number ASC, songs.rowid ASC
            LIMIT 1
        )
        WHERE EXISTS (
            SELECT 1 FROM songs
            WHERE songs.album_id = albums.id AND songs.is_missing = 0
        )
        ORDER BY albums.title COLLATE NOCASE ASC
        """
    )
    fun getAllAlbums(): Flow<List<AlbumWithArtist>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getAlbumById(id: String): AlbumEntity?

    // Unfiltered counterpart to getAllAlbums (which excludes albums whose songs are all
    // missing) — the playlist export needs a missing song's album title too.
    @Query("SELECT * FROM albums")
    suspend fun getAllAlbumsOnce(): List<AlbumEntity>

    @Query("UPDATE albums SET album_art_uri = :albumArtUri WHERE id = :id")
    suspend fun updateAlbumArt(id: String, albumArtUri: String?)

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT album_id FROM songs WHERE album_id IS NOT NULL)")
    suspend fun deleteOrphanAlbums()
}
