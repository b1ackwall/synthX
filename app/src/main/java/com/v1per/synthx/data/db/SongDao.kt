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

    @Transaction
    @Query(
        """
        SELECT * FROM songs
        WHERE is_missing = 0
        ORDER BY date_added DESC
        LIMIT :limit
        """
    )
    fun getRecentSongsWithDetails(limit: Int): Flow<List<SongWithDetails>>

    // Room batches the @Relation loads, same as getSongsWithDetailsForArtist/Album — joins
    // straight to playlist_songs so the result set is the playlist, not the whole library.
    @Transaction
    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_songs ON playlist_songs.song_id = songs.id
        WHERE playlist_songs.playlist_id = :playlistId
        AND songs.is_missing = 0
        ORDER BY playlist_songs.position ASC
        """
    )
    fun getSongsWithDetailsForPlaylist(playlistId: String): Flow<List<SongWithDetails>>

    // Starred = not yet organized into a playlist. Filtered in SQL (not in Kotlin off
    // getAllSongsWithDetails) for the same reason getSongsWithDetailsForPlaylist joins
    // straight to playlist_songs: the result set should be the starred songs, not the whole
    // library narrowed down afterwards. Newest first — the useful order for a "what still
    // needs filing" list.
    @Transaction
    @Query(
        """
        SELECT * FROM songs
        WHERE in_inbox = 1
        AND is_missing = 0
        ORDER BY date_added DESC
        """
    )
    fun getStarredSongs(): Flow<List<SongWithDetails>>

    // Badge on the Library screen's Starred row. A dedicated COUNT rather than
    // getStarredSongs().map { it.size }: that would materialize and artist-join the whole
    // starred list just to measure it. Same shape as getMissingSongCount above, and the
    // predicate is kept identical to getStarredSongs' so the badge can never disagree with
    // the list it describes.
    @Query("SELECT COUNT(*) FROM songs WHERE in_inbox = 1 AND is_missing = 0")
    fun getStarredSongCount(): Flow<Int>

    /**
     * Whether the song belongs to at least one playlist — gates the "Remove from Starred"
     * action, which exists to file away a song the user has already organized.
     *
     * Same EXISTS shape as PlaylistDao.containsSong, minus the playlist_id predicate.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM playlist_songs WHERE song_id = :songId)")
    suspend fun isInAnyPlaylist(songId: String): Boolean

    // Deliberately one-way: nothing sets in_inbox back to 1 for an existing song, so a
    // dismissal sticks even if the song later leaves every playlist it was in.
    @Query("UPDATE songs SET in_inbox = 0 WHERE id = :id")
    suspend fun dismissFromStarred(id: String)

    @Query("SELECT * FROM song_artists WHERE song_id = :songId ORDER BY position ASC")
    suspend fun getSongArtistRefs(songId: String): List<SongArtistEntity>

    @Query("SELECT * FROM song_artists ORDER BY position ASC")
    suspend fun getAllSongArtistRefs(): List<SongArtistEntity>

    // Scoped variant of getAllSongArtistRefs for callers that already know their result
    // set — a filtered/playlist query joining the whole song_artists table to look up
    // refs for a handful of songs re-fetches every row in the library on each emission.
    @Query("SELECT * FROM song_artists WHERE song_id IN (:songIds) ORDER BY position ASC")
    suspend fun getSongArtistRefsForSongs(songIds: List<String>): List<SongArtistEntity>

    @Query("SELECT * FROM songs")
    suspend fun getAllSongsOnce(): List<SongEntity>

    /** Cheap total library size — used to compute a scan's impact as a percentage. */
    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCount(): Int

    // Deliberately unfiltered by is_missing, unlike getSongsWithDetailsForPlaylist — the
    // playlist export needs a missing song's last-known title/artist/filePath too, not just
    // the songs still playable.
    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<String>): List<SongEntity>

    /** Same id-set lookup as [getSongsByIds], joined to album/artist details for domain mapping. */
    @Transaction
    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsWithDetailsByIds(ids: List<String>): List<SongWithDetails>

    @Query("UPDATE songs SET is_missing = 1, missing_since = :missingSince WHERE id = :id")
    suspend fun markMissing(id: String, missingSince: Long)

    // file_path carries a UNIQUE index, so a stale row being flagged missing (rather than
    // deleted) must give up its path before a new row can claim it for the same on-disk
    // file. Blanked to a per-id sentinel rather than "" — multiple stale rows can coexist
    // over the life of the install, and "" would collide on the second one.
    @Query("UPDATE songs SET file_path = 'stale://' || id WHERE id = :id")
    suspend fun clearFilePathForRetag(id: String)

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
