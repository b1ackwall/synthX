package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.v1per.synthx.data.repository.PlaylistMembership
import kotlinx.coroutines.flow.Flow

data class PlaylistMemberCount(val playlistId: String, val memberCount: Int)

@Dao
interface PlaylistDao {

    // Groups

    @Query("SELECT * FROM playlist_groups ORDER BY name COLLATE NOCASE ASC")
    fun getAllGroups(): Flow<List<PlaylistGroupEntity>>

    // Deliberately NOT OnConflictStrategy.REPLACE: the only production caller
    // (SongRepositoryImpl.createGroup) always inserts a freshly generated UUID, so a conflict here
    // can only mean an id collision bug. REPLACE would silently delete the existing
    // row first — orphaning every playlist under it (group_id is ON DELETE SET NULL) — which is
    // far worse than the default ABORT throwing loudly.
    @Insert
    suspend fun insertGroup(group: PlaylistGroupEntity)

    @Query("DELETE FROM playlist_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("UPDATE playlist_groups SET name = :name WHERE id = :id")
    suspend fun renameGroup(id: String, name: String)

    // Playlists

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE group_id IS NULL ORDER BY name COLLATE NOCASE ASC")
    fun getUngroupedPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE group_id = :groupId ORDER BY name COLLATE NOCASE ASC")
    fun getPlaylistsForGroup(groupId: String): Flow<List<PlaylistEntity>>

    // Deliberately NOT OnConflictStrategy.REPLACE: SongRepositoryImpl.createPlaylist always
    // inserts a freshly generated UUID, so a conflict here can only mean an id collision bug.
    // REPLACE would silently delete the existing row first — cascading away every membership
    // under it (playlist_id is ON DELETE CASCADE on playlist_songs) — which is far worse than
    // the default ABORT throwing loudly.
    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String, updatedAt: Long)

    @Query("UPDATE playlists SET group_id = :groupId, updated_at = :updatedAt WHERE id = :id")
    suspend fun movePlaylistToGroup(id: String, groupId: String?, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    // Playlist art (see PlaylistEntity.artHue)

    /**
     * Feeds the farthest-point spreading in PlaylistArtHue.selectDistancedHue. One row per
     * playlist, duplicates included — the selection counts them. Reduced to 0..359 here (the
     * double modulo because SQLite's `%` keeps the dividend's sign) so a restored or hand-edited
     * out-of-range value is read as the hue it wraps to; nothing is rewritten in the table.
     */
    @Query("SELECT ((art_hue % 360) + 360) % 360 FROM playlists WHERE art_hue IS NOT NULL")
    suspend fun getExistingArtHues(): List<Int>

    /**
     * PlaylistArtBackfill's work list. Either column being NULL counts as missing — both are
     * needed to render, and they are only ever written together. Ordered by creation so the
     * backfill assigns hues to a library's oldest playlists first, giving the same result
     * whatever order the rows happen to sit in. `id` is a tie-breaker:
     * Rows sharing the SAME `created_at` (any batch inserted with one timestamp) would otherwise
     * tie on the primary sort and come back in whatever order SQLite happens to return them —
     * not reproducible across devices/runs.
     */
    @Query(
        "SELECT id FROM playlists WHERE art_hue IS NULL OR art_structure_seed IS NULL " +
            "ORDER BY created_at ASC, id ASC",
    )
    suspend fun getPlaylistIdsMissingArt(): List<String>

    /**
     * Written once per playlist and immutable afterwards — there is deliberately no regenerate
     * path. The `IS NULL` guard makes this idempotent: if two backfill passes
     * somehow overlap and both pick a (possibly different) hue for the same row, only the first
     * write to actually land sticks — the second becomes a no-op rather than silently re-rolling
     * art that was already decided.
     */
    @Query(
        "UPDATE playlists SET art_hue = :hue, art_structure_seed = :seed " +
            "WHERE id = :id AND art_hue IS NULL AND art_structure_seed IS NULL",
    )
    suspend fun setPlaylistArt(id: String, hue: Int, seed: Long)

    // Playlist songs

    @Query(
        "SELECT song_id FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY position ASC",
    )
    suspend fun getSongIdsForPlaylist(playlistId: String): List<String>

    @Query("SELECT playlist_id FROM playlist_songs WHERE song_id = :songId")
    suspend fun getPlaylistIdsForSong(songId: String): List<String>

    /**
     * Richer counterpart to [getPlaylistIdsForSong] — the whole membership row, not just which
     * playlist it names. Kept separate rather than widening that one, which drives the
     * Add-to-Playlist indicator and only ever needs the ids. See [PlaylistMembership].
     */
    @Query("SELECT playlist_id, position, added_at FROM playlist_songs WHERE song_id = :songId")
    suspend fun getPlaylistMembershipsForSong(songId: String): List<PlaylistMembership>

    /** Aggregate used by delete preview; one bounded query replaces an N-query count loop. */
    @Query(
        "SELECT playlist_id AS playlistId, COUNT(*) AS memberCount FROM playlist_songs " +
            "WHERE playlist_id IN (:ids) GROUP BY playlist_id",
    )
    suspend fun getPlaylistMemberCounts(ids: List<String>): List<PlaylistMemberCount>

    /** Names for a known set of playlist ids — for describing a membership set without
     *  collecting the whole [getAllPlaylists] Flow. */
    @Query("SELECT * FROM playlists WHERE id IN (:ids)")
    suspend fun getPlaylistsByIds(ids: List<String>): List<PlaylistEntity>

    /**
     * The only primitive that writes an *explicit* position — [addSongToPlaylist] below always
     * appends. REPLACE-on-conflict is load-bearing for Replace Song: the primary key is
     * (playlist_id, song_id), so re-inserting a song already in the playlist moves it to the
     * given position rather than failing or duplicating it. Ordinary "add to playlist" must not
     * use this directly for exactly that reason — see [addSongToPlaylist]'s kdoc.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun removePlaylistSong(playlistId: String, songId: String)

    /** Null when the playlist is empty — callers append at 0 in that case. */
    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun getMaxPosition(playlistId: String): Int?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM playlist_songs " +
            "WHERE playlist_id = :playlistId AND song_id = :songId)",
    )
    suspend fun containsSong(playlistId: String, songId: String): Boolean

    /**
     * Contains-check, max-position read, and insert must happen as one
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
            ),
        )
        return true
    }
}
