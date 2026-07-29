package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun getAllArtists(): Flow<List<ArtistEntity>>

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN song_artists ON songs.id = song_artists.song_id
        WHERE song_artists.artist_id = :artistId
        AND songs.is_missing = 0
        ORDER BY songs.title COLLATE NOCASE ASC
        """
    )
    suspend fun getSongsForArtist(artistId: String): List<SongEntity>

    // TODO v2.x: orphan cleanup
}
