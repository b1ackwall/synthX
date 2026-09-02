package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.v1per.synthx.data.repository.QueueSlotRecord

@Dao
interface QueueDao {

    /** Raw persisted slots in active-timeline order; song resolution happens in the repository. */
    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    suspend fun getQueueItems(): List<QueueItemEntity>

    @Query("DELETE FROM queue_items")
    suspend fun clearQueue()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueItems(items: List<QueueItemEntity>)

    /**
     * Replaces the whole queue in one atomic step — `position` follows list order.
     * The queue is rewritten on every mutation, so there is no per-row state worth preserving.
     */
    @Transaction
    suspend fun replaceQueue(slots: List<QueueSlotRecord>) {
        clearQueue()
        insertQueueItems(
            slots.mapIndexed { index, slot ->
                QueueItemEntity(slotId = slot.slotId, position = index, songId = slot.songId)
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
    @Transaction
    suspend fun updateCursor(currentSlotId: String?, positionMs: Long) {
        val previous = getCursor()
        upsertCursor(
            PlaybackCursorEntity(
                currentSlotId = currentSlotId,
                positionMs = positionMs,
                shuffleEnabled = previous?.shuffleEnabled ?: false,
                repeatMode = previous?.repeatMode ?: 0,
            )
        )
    }

    /**
     * The atomic write this table pair exists for: queue structure and cursor land in the
     * same transaction, so a process death can never leave one persisted without the other.
     * [currentSlotId] identifies the exact occurrence the caller wants to resume from.
     */
    @Transaction
    suspend fun replaceQueueAndCursor(
        slots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
    ) {
        clearQueue()
        insertQueueItems(
            slots.mapIndexed { index, slot ->
                QueueItemEntity(slotId = slot.slotId, position = index, songId = slot.songId)
            }
        )
        val previous = getCursor()
        upsertCursor(
            PlaybackCursorEntity(
                currentSlotId = currentSlotId,
                positionMs = positionMs,
                shuffleEnabled = previous?.shuffleEnabled ?: false,
                repeatMode = previous?.repeatMode ?: 0,
            )
        )
    }

    /** Reads queue slots and cursor as one consistent snapshot, rather than two separate queries. */
    @Transaction
    suspend fun getQueueAndCursor(): Pair<List<QueueItemEntity>, PlaybackCursorEntity?> =
        getQueueItems() to getCursor()

    @Query("UPDATE playback_cursor SET shuffle_enabled = :enabled WHERE id = 0")
    suspend fun updateShuffleEnabledRaw(enabled: Boolean): Int

    @Query("UPDATE playback_cursor SET repeat_mode = :mode WHERE id = 0")
    suspend fun updateRepeatModeRaw(mode: Int): Int

    /**
     * Shuffle-only mode write — leaves `current_slot_id`/`position_ms` (and `repeat_mode`)
     * untouched. A shuffle toggle doesn't coincide with a cursor or queue-structure change,
     * so this is its own narrow update rather than routed through [replaceQueueAndCursor] or
     * [updateCursor]. Falls back to inserting a fresh row only when no cursor exists yet
     * (shuffle toggled before anything was ever persisted) — the common case is a plain
     * UPDATE against the row [replaceQueueAndCursor]/[updateCursor] already maintain.
     */
    @Transaction
    suspend fun updateShuffleEnabled(enabled: Boolean) {
        if (updateShuffleEnabledRaw(enabled) == 0) {
            upsertCursor(PlaybackCursorEntity(currentSlotId = null, positionMs = 0L, shuffleEnabled = enabled))
        }
    }

    /** Repeat-mode counterpart of [updateShuffleEnabled] — same narrow-update shape. */
    @Transaction
    suspend fun updateRepeatMode(mode: Int) {
        if (updateRepeatModeRaw(mode) == 0) {
            upsertCursor(PlaybackCursorEntity(currentSlotId = null, positionMs = 0L, repeatMode = mode))
        }
    }

    // --- baseline order (mirrors queue_items above; see BaselineQueueItemEntity's kdoc) ---

    @Query("SELECT * FROM queue_baseline_items ORDER BY position ASC")
    suspend fun getBaselineQueueItems(): List<BaselineQueueItemEntity>

    @Query("DELETE FROM queue_baseline_items")
    suspend fun clearBaselineQueue()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBaselineQueueItems(items: List<BaselineQueueItemEntity>)

    @Transaction
    suspend fun replaceBaselineQueue(slots: List<QueueSlotRecord>) {
        clearBaselineQueue()
        insertBaselineQueueItems(
            slots.mapIndexed { index, slot ->
                BaselineQueueItemEntity(slotId = slot.slotId, position = index, songId = slot.songId)
            }
        )
    }
}
