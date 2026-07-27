package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ArtistDao {

    @Upsert
    suspend fun upsertArtist(artist: ArtistEntity)

    @Query("SELECT * FROM artists WHERE name_normalized = :normalized LIMIT 1")
    suspend fun getArtistByNormalizedName(normalized: String): ArtistEntity?

    @Query(
        """
        SELECT artists.* FROM artists
        INNER JOIN song_artists ON song_artists.artist_id = artists.id
        WHERE song_artists.song_id = :songId
        ORDER BY song_artists.position
        """
    )
    suspend fun getArtistsForSong(songId: String): List<ArtistEntity>

    // TODO v2.x: getAllArtists for the Library artists tab, orphan cleanup
}
