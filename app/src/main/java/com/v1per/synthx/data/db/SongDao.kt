package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.v1per.synthx.data.repository.MissingSongInfo
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
    fun getSongsWithDetailsForArtist(artistId: String): Flow<List<SongWithDetails>>

    @Transaction
    @Query(
        """
        SELECT * FROM songs
        WHERE album_id = :albumId
        AND is_missing = 0
        ORDER BY track_number IS NULL, track_number ASC, title COLLATE NOCASE ASC
        """
    )
    fun getSongsWithDetailsForAlbum(albumId: String): Flow<List<SongWithDetails>>

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

    // Primary artist = the position-0 entry in song_artists, resolved by correlated
    // subquery so the projection stays one row per song. missing_since is guaranteed
    // non-null by the is_missing = 1 filter, but COALESCE keeps SQLite from handing
    // Room a null if a row is ever written inconsistently.
    @Query(
        """
        SELECT
            songs.id AS id,
            songs.title AS title,
            COALESCE(artists.name, 'Unknown Artist') AS artist,
            COALESCE(songs.missing_since, 0) AS missing_since
        FROM songs
        LEFT JOIN artists ON artists.id = (
            SELECT song_artists.artist_id
            FROM song_artists
            WHERE song_artists.song_id = songs.id AND song_artists.position = 0
            LIMIT 1
        )
        WHERE songs.is_missing = 1
        ORDER BY songs.missing_since DESC
        """
    )
    fun getMissingSongs(): Flow<List<MissingSongInfo>>

    @Query("SELECT COUNT(*) FROM songs WHERE is_missing = 1")
    fun getMissingSongCount(): Flow<Int>

    // Art URI of any other song still in the album, for re-pointing albums.album_art_uri
    // when the song that supplied it is deleted. Prefers a song that actually has art so
    // the album doesn't lose its cover to a song that never had one.
    @Query(
        """
        SELECT album_art_uri FROM songs
        WHERE album_id = :albumId
          AND id != :excludeSongId
          AND album_art_uri IS NOT NULL
        LIMIT 1
        """
    )
    suspend fun findReplacementAlbumArt(albumId: String, excludeSongId: String): String?

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSongById(id: String)

    // TODO v2.x: verification pass (markVerified), sort variants, missing-song queries
}
