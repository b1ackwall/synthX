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
     * gone missing are filtered out here, the same way [SongDao.getSongsWithDetailsForPlaylist]
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
     * The queue is rewritten on every mutation, so there is no per-row state worth preserving.
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

    // --- cursor (see PlaybackCursorEntity's kdoc) ---

    @Query("SELECT * FROM playback_cursor WHERE id = 0")
    suspend fun getCursor(): PlaybackCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: PlaybackCursorEntity)

    /**
     * Cursor-only update — leaves `queue_items` completely untouched. For call sites where the
     * queue structure hasn't changed and rewriting it would be pure waste (e.g. a track
     * transition during ordinary playback), as opposed to [replaceQueueAndCursor], which is for
     * call sites where the structure itself might also need to be (re)confirmed.
     */
    suspend fun updateCursor(queueIndex: Int, positionMs: Long) {
        upsertCursor(PlaybackCursorEntity(queueIndex = queueIndex, positionMs = positionMs))
    }

    /**
     * The atomic write this table pair exists for: queue structure and cursor land in the
     * same transaction, so a process death can never leave one persisted without the other.
     * [queueIndex] is the raw timeline index the caller wants to resume from; it always
     * describes a position within [songIds] as written here, in the same commit.
     */
    @Transaction
    suspend fun replaceQueueAndCursor(songIds: List<String>, queueIndex: Int, positionMs: Long) {
        clearQueue()
        insertQueueItems(
            songIds.mapIndexed { index, songId ->
                QueueItemEntity(position = index, songId = songId)
            }
        )
        upsertCursor(PlaybackCursorEntity(queueIndex = queueIndex, positionMs = positionMs))
    }

    /** Reads queue ids and cursor as one consistent snapshot, rather than two separate queries. */
    @Transaction
    suspend fun getQueueAndCursor(): Pair<List<String>, PlaybackCursorEntity?> =
        getQueueSongIds() to getCursor()

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
