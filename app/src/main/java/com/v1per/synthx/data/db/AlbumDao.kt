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

    @Query("SELECT * FROM albums WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun getAlbumByTitle(title: String): AlbumEntity?

    /**
     * Look up an album by title, inserting it if absent. Atomic so two concurrent
     * scans cannot both miss the lookup and mint duplicate rows — `title` is
     * indexed but not unique, so SQLite would accept both.
     */
    @Transaction
    suspend fun getOrInsertAlbum(title: String, year: Int?, albumArtUri: String?): AlbumEntity {
        getAlbumByTitle(title)?.let { return it }
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
        ORDER BY albums.title COLLATE NOCASE ASC
        """
    )
    fun getAllAlbums(): Flow<List<AlbumWithArtist>>

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT album_id FROM songs WHERE album_id IS NOT NULL)")
    suspend fun deleteOrphanAlbums()
}
