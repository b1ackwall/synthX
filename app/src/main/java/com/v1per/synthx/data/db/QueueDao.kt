package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.v1per.synthx.data.repository.QueueExtraTag
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

    // --- cursor (see PlaybackCursorEntity's kdoc) ---

    @Query("SELECT * FROM playback_cursor WHERE id = 0")
    suspend fun getCursor(): PlaybackCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: PlaybackCursorEntity)

    /**
     * Cursor-only update — leaves `queue_items` completely untouched. For call sites where the
     * queue structure hasn't changed and rewriting it would be pure waste (e.g. a track
     * transition during ordinary playback), as opposed to [replaceQueueAndCursor], which is for
     * call sites where the structure itself might also need to be (re)confirmed. The modes and
     * the reorder flag are copied from the stored row.
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
                shuffleReordered = previous?.shuffleReordered ?: false,
            ),
        )
    }

    /**
     * The atomic write this table pair exists for: queue structure and cursor land in the
     * same transaction, so a process death can never leave one persisted without the other.
     * [currentSlotId] identifies the exact occurrence the caller wants to resume from.
     *
     * [extras] tags the rows of the session extras. [shuffleReordered] is the reorder flag to
     * store; null copies the stored one, like the modes, which are always copied.
     */
    @Transaction
    suspend fun replaceQueueAndCursor(
        slots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
        extras: Map<String, QueueExtraTag>,
        shuffleReordered: Boolean?,
    ) {
        clearQueue()
        insertQueueItems(queueItemRows(slots, extras))
        val previous = getCursor()
        upsertCursor(
            PlaybackCursorEntity(
                currentSlotId = currentSlotId,
                positionMs = positionMs,
                shuffleEnabled = previous?.shuffleEnabled ?: false,
                repeatMode = previous?.repeatMode ?: 0,
                shuffleReordered = shuffleReordered ?: previous?.shuffleReordered ?: false,
            ),
        )
    }

    /** Reads queue slots and cursor as one consistent snapshot, rather than two separate queries. */
    @Transaction
    suspend fun getQueueAndCursor(): Pair<List<QueueItemEntity>, PlaybackCursorEntity?> = getQueueItems() to getCursor()

    @Query("UPDATE playback_cursor SET shuffle_enabled = :enabled WHERE id = 0")
    suspend fun updateShuffleEnabledRaw(enabled: Boolean): Int

    @Query("UPDATE playback_cursor SET repeat_mode = :mode WHERE id = 0")
    suspend fun updateRepeatModeRaw(mode: Int): Int

    /**
     * Shuffle-only mode write — leaves `current_slot_id`/`position_ms` (and `repeat_mode` and
     * `shuffle_reordered`) untouched. A shuffle toggle doesn't coincide with a cursor or queue-structure change,
     * so this is its own narrow update rather than routed through [replaceQueueAndCursor] or
     * [updateCursor]. Falls back to inserting a fresh row only when no cursor exists yet
     * (shuffle toggled before anything is persisted), which has no reorder flag to keep —
     * the common case is a plain UPDATE against the row [replaceQueueAndCursor]/[updateCursor]
     * already maintain.
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
            },
        )
    }

    /**
     * The atomic write for every call site that changes more than one of {baseline order, active
     * order, shuffle flag, repeat mode, cursor} at once — a fresh play and a
     * shuffle toggle. All five pieces land in the same transaction,
     * so an interrupted write can never leave some already changed and others still at their
     * previous values, as separate commits through
     * [replaceBaselineQueue]/[updateShuffleEnabled]/[updateRepeatMode]/[updateCursor]/
     * [replaceQueueAndCursor] could. Narrow single-piece
     * call sites (a plain track transition, Play Next, a drag-reorder) keep using those.
     * The session-extra tags [extras] and the reorder flag [shuffleReordered] land in the same
     * transaction.
     */
    @Transaction
    suspend fun replaceQueueBaselineAndCursor(
        activeSlots: List<QueueSlotRecord>,
        baselineSlots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
        extras: Map<String, QueueExtraTag>,
        shuffleReordered: Boolean,
    ) {
        clearQueue()
        insertQueueItems(queueItemRows(activeSlots, extras))
        clearBaselineQueue()
        insertBaselineQueueItems(
            baselineSlots.mapIndexed { index, slot ->
                BaselineQueueItemEntity(slotId = slot.slotId, position = index, songId = slot.songId)
            },
        )
        upsertCursor(
            PlaybackCursorEntity(
                currentSlotId = currentSlotId,
                positionMs = positionMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                shuffleReordered = shuffleReordered,
            ),
        )
    }

    /**
     * Reads the active queue, baseline order, and cursor together as one consistent snapshot
     * — the read-side counterpart of [replaceQueueBaselineAndCursor], closing the
     * split-read window independent suspending queries would otherwise leave open against an
     * atomically-written trio. Used by the live-reconnect adoption path.
     */
    @Transaction
    suspend fun getFullQueueSnapshot():
        Triple<List<QueueItemEntity>, List<BaselineQueueItemEntity>, PlaybackCursorEntity?> =
        Triple(getQueueItems(), getBaselineQueueItems(), getCursor())
}

/** Live-order rows in [slots] order, each tagged when [extras] names its slot. */
internal fun queueItemRows(slots: List<QueueSlotRecord>, extras: Map<String, QueueExtraTag>): List<QueueItemEntity> =
    slots.mapIndexed { index, slot ->
        val tag = extras[slot.slotId]
        QueueItemEntity(
            slotId = slot.slotId,
            position = index,
            songId = slot.songId,
            extraKind = tag?.kind?.storedValue,
            extraSequence = tag?.sequence,
        )
    }
