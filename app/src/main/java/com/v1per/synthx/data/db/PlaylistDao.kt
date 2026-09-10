package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    // Playlist art (see PlaylistEntity.artHue)

    /** Feeds the farthest-point spreading in PlaylistArtHue.selectDistancedHue. */
    @Query("SELECT art_hue FROM playlists WHERE art_hue IS NOT NULL")
    suspend fun getExistingArtHues(): List<Int>

    /**
     * PlaylistArtBackfill's work list. Either column being NULL counts as missing — both are
     * needed to render, and they are only ever written together. Ordered by creation so the
     * backfill assigns hues to a library's oldest playlists first, giving the same result
     * whatever order the rows happen to sit in.
     */
    @Query(
        "SELECT id FROM playlists WHERE art_hue IS NULL OR art_structure_seed IS NULL " +
            "ORDER BY created_at ASC"
    )
    suspend fun getPlaylistIdsMissingArt(): List<String>

    /** Written once per playlist and immutable afterwards — there is deliberately no regenerate path. */
    @Query("UPDATE playlists SET art_hue = :hue, art_structure_seed = :seed WHERE id = :id")
    suspend fun setPlaylistArt(id: String, hue: Int, seed: Long)

    // Playlist songs

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

    /**
     * P7.6/N9/6.6: contains-check, max-position read, and insert must happen as one
     * @Transaction — otherwise two concurrent adds to the same playlist can both read the
     * same max position before either write commits, landing two rows at the identical
     * position (nothing constrains (playlist_id, position); only (playlist_id, song_id) is
     * keyed), leaving ORDER BY position with an undefined tie. Checked rather than relying
     * on REPLACE-on-conflict: that would silently move an already-present song to the end,
     * which reads as the playlist reordering itself.
     *
     * Returns false without inserting if the song is already in the playlist.
     */
    @Transaction
    suspend fun addSongToPlaylist(playlistId: String, songId: String, addedAt: Long): Boolean {
        if (containsSong(playlistId, songId)) {
            return false
        }
        val nextPosition = (getMaxPosition(playlistId) ?: -1) + 1
        insertPlaylistSong(
            PlaylistSongEntity(
                playlistId = playlistId,
                songId = songId,
                position = nextPosition,
                addedAt = addedAt,
            )
        )
        return true
    }
}
