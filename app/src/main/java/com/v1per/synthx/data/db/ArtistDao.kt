package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {

    @Upsert
    suspend fun upsertArtist(artist: ArtistEntity)

    @Query("SELECT * FROM artists WHERE name_normalized = :normalized LIMIT 1")
    suspend fun getArtistByNormalizedName(normalized: String): ArtistEntity?

    /**
     * Look up an artist by its normalized name, inserting it if absent. Atomic so
     * two concurrent scans cannot both miss the lookup and mint duplicate rows —
     * `name_normalized` is indexed but not unique, so SQLite would accept both.
     */
    @Transaction
    suspend fun getOrInsertArtist(name: String, normalized: String): ArtistEntity {
        getArtistByNormalizedName(normalized)?.let { return it }
        val artist = ArtistEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            nameNormalized = normalized,
        )
        upsertArtist(artist)
        return artist
    }

    // Artists where every associated song is_missing = 1 (or that have zero songs
    // at all) are excluded via the EXISTS clause below, so they don't linger as
    // dead entries — mirroring how missing songs already don't show up as
    // tappable rows elsewhere.
    @Query(
        """
        SELECT * FROM artists
        WHERE EXISTS (
            SELECT 1 FROM songs
            INNER JOIN song_artists ON song_artists.song_id = songs.id
            WHERE song_artists.artist_id = artists.id AND songs.is_missing = 0
        )
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    fun getAllArtists(): Flow<List<ArtistEntity>>

    // Unfiltered counterpart to getAllArtists, same shape as SongDao.getAllSongsOnce — the
    // playlist export needs every artist name, including ones whose only songs are missing.
    @Query("SELECT * FROM artists")
    suspend fun getAllArtistsOnce(): List<ArtistEntity>

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT DISTINCT artist_id FROM song_artists)")
    suspend fun deleteOrphanArtists()
}
