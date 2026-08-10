package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface QueueDao {

    /**
     * The persisted queue joined to song details, in position order. Rows whose song has
     * gone missing are filtered out here, the same way [PlaylistDao.getSongsForPlaylist]
     * does — so this can return fewer entries than [getQueueSongIds].
     */
    @Transaction
    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN queue_items ON queue_items.song_id = songs.id
        WHERE songs.is_missing = 0
        ORDER BY queue_items.position ASC
        """
    )
    suspend fun getQueueItems(): List<SongWithDetails>

    /** The raw stored ids, including any that [getQueueItems] drops — used to re-find the saved index. */
    @Query("SELECT song_id FROM queue_items ORDER BY position ASC")
    suspend fun getQueueSongIds(): List<String>

    @Query("DELETE FROM queue_items")
    suspend fun clearQueue()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueItems(items: List<QueueItemEntity>)

    /**
     * Replaces the whole queue in one atomic step — `position` follows list order.
     * Clear-and-reinsert, matching [PlaylistDao.replacePlaylistSongs]: the queue is
     * rewritten on every mutation anyway, so there is no per-row state worth preserving.
     */
    @Transaction
    suspend fun replaceQueue(songIds: List<String>) {
        clearQueue()
        insertQueueItems(
            songIds.mapIndexed { index, songId ->
                QueueItemEntity(position = index, songId = songId)
            }
        )
    }

    // --- baseline order (mirrors queue_items above; see BaselineQueueItemEntity's kdoc) ---

    @Transaction
    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN queue_baseline_items ON queue_baseline_items.song_id = songs.id
        WHERE songs.is_missing = 0
        ORDER BY queue_baseline_items.position ASC
        """
    )
    suspend fun getBaselineQueueItems(): List<SongWithDetails>

    @Query("SELECT song_id FROM queue_baseline_items ORDER BY position ASC")
    suspend fun getBaselineQueueSongIds(): List<String>

    @Query("DELETE FROM queue_baseline_items")
    suspend fun clearBaselineQueue()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBaselineQueueItems(items: List<BaselineQueueItemEntity>)

    @Transaction
    suspend fun replaceBaselineQueue(songIds: List<String>) {
        clearBaselineQueue()
        insertBaselineQueueItems(
            songIds.mapIndexed { index, songId ->
                BaselineQueueItemEntity(position = index, songId = songId)
            }
        )
    }
}
