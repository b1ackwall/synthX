package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Upsert
    suspend fun upsertSong(song: SongEntity)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE file_path = :path")
    suspend fun getSongByPath(path: String): SongEntity?

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Transaction
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun getAllSongsWithDetails(): Flow<List<SongWithDetails>>

    // Room batches the @Relation loads, so these cost 3 queries total rather than
    // 2 per song. Callers still need getAllSongArtistRefs to restore artist order.
    @Transaction
    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN song_artists ON songs.id = song_artists.song_id
        WHERE song_artists.artist_id = :artistId
        AND songs.is_missing = 0
        ORDER BY songs.title COLLATE NOCASE ASC
        """
    )
    suspend fun getSongsWithDetailsForArtist(artistId: String): List<SongWithDetails>

    @Transaction
    @Query(
        """
        SELECT * FROM songs
        WHERE album_id = :albumId
        AND is_missing = 0
        ORDER BY track_number IS NULL, track_number ASC, title COLLATE NOCASE ASC
        """
    )
    suspend fun getSongsWithDetailsForAlbum(albumId: String): List<SongWithDetails>

    @Query("SELECT * FROM song_artists WHERE song_id = :songId ORDER BY position ASC")
    suspend fun getSongArtistRefs(songId: String): List<SongArtistEntity>

    @Query("SELECT * FROM song_artists ORDER BY position ASC")
    suspend fun getAllSongArtistRefs(): List<SongArtistEntity>

    @Query("SELECT * FROM songs")
    suspend fun getAllSongsOnce(): List<SongEntity>

    @Query("UPDATE songs SET is_missing = 1, missing_since = :missingSince WHERE id = :id")
    suspend fun markMissing(id: String, missingSince: Long)

    @Query("UPDATE songs SET is_missing = 0, missing_since = NULL WHERE id = :id")
    suspend fun markFound(id: String)

    @Query("SELECT * FROM songs WHERE verified = 0")
    fun getUnverifiedSongs(): Flow<List<SongEntity>>

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSongById(id: String)

    // TODO v2.x: verification pass (markVerified), sort variants, missing-song queries
}
