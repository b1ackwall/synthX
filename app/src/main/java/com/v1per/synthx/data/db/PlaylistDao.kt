package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    // Groups

    @Query("SELECT * FROM playlist_groups ORDER BY name COLLATE NOCASE ASC")
    fun getAllGroups(): Flow<List<PlaylistGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: PlaylistGroupEntity)

    @Query("DELETE FROM playlist_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("UPDATE playlist_groups SET name = :name WHERE id = :id")
    suspend fun renameGroup(id: String, name: String)

    // Playlists

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun getPlaylistCount(): Int

    @Query("SELECT * FROM playlists WHERE group_id IS NULL ORDER BY name COLLATE NOCASE ASC")
    fun getUngroupedPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE group_id = :groupId ORDER BY name COLLATE NOCASE ASC")
    fun getPlaylistsForGroup(groupId: String): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String, updatedAt: Long)

    @Query("UPDATE playlists SET group_id = :groupId, updated_at = :updatedAt WHERE id = :id")
    suspend fun movePlaylistToGroup(id: String, groupId: String?, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    // Playlist songs

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_songs ON playlist_songs.song_id = songs.id
        WHERE playlist_songs.playlist_id = :playlistId
        AND songs.is_missing = 0
        ORDER BY playlist_songs.position ASC
        """
    )
    fun getSongsForPlaylist(playlistId: String): Flow<List<SongEntity>>

    @Query(
        "SELECT song_id FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY position ASC"
    )
    suspend fun getSongIdsForPlaylist(playlistId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun removePlaylistSong(playlistId: String, songId: String)

    /** Null when the playlist is empty — callers append at 0 in that case. */
    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun getMaxPosition(playlistId: String): Int?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM playlist_songs " +
            "WHERE playlist_id = :playlistId AND song_id = :songId)"
    )
    suspend fun containsSong(playlistId: String, songId: String): Boolean
}
