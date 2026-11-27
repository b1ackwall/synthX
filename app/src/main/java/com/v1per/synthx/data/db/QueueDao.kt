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

    /** The `position` of [slotId]'s live row, or null when it has none. */
    @Query("SELECT position FROM queue_items WHERE slot_id = :slotId")
    suspend fun queuePositionOf(slotId: String): Int?

    /**
     * Cursor-only update — leaves `queue_items` completely untouched. For call sites where the
     * queue structure hasn't changed and rewriting it would be pure waste (e.g. a track
     * transition during ordinary playback), as opposed to [replaceQueueAndCursor], which is for
     * call sites where the structure itself might also need to be (re)confirmed. The modes, the
     * reorder flag and the customised-queue flag are copied from the stored row; the queue position
     * follows [currentSlotId] (see [currentQueuePositionFor]).
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
                queueCustomized = previous?.queueCustomized ?: false,
                currentQueuePosition = currentQueuePositionFor(currentSlotId, previous),
            ),
        )
    }

    /**
     * The atomic write this table pair exists for: queue structure and cursor land in the
     * same transaction, so a process death can never leave one persisted without the other.
     * [currentSlotId] identifies the exact occurrence the caller wants to resume from.
     *
     * [extras] tags the rows of the session extras. [shuffleReordered] and [queueCustomized] are
     * the reorder flag and the customised-queue flag to store; null copies the stored one, like the
     * modes, which are always copied. The queue position is read from the rows just written.
     */
    @Transaction
    suspend fun replaceQueueAndCursor(
        slots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
        extras: Map<String, QueueExtraTag>,
        shuffleReordered: Boolean?,
        queueCustomized: Boolean?,
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
                queueCustomized = queueCustomized ?: previous?.queueCustomized ?: false,
                currentQueuePosition = currentQueuePositionFor(currentSlotId, previous),
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
     * Shuffle-only mode write — leaves `current_slot_id`/`position_ms` (and `repeat_mode`,
     * `shuffle_reordered`, `queue_customized` and `current_queue_position`) untouched. A shuffle
     * toggle doesn't coincide with a cursor or queue-structure change, so this is its own narrow
     * update rather than routed through [replaceQueueAndCursor] or [updateCursor]. Falls back to
     * inserting a fresh row only when no cursor exists yet (shuffle toggled before anything is
     * persisted), which has no flag or position to keep — the common case is a plain UPDATE
     * against the row [replaceQueueAndCursor]/[updateCursor] already maintain.
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
     * The session-extra tags [extras], the reorder flag [shuffleReordered] and the customised-queue
     * flag [queueCustomized] land in the same transaction, as given; the queue position is read from
     * the rows just written.
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
        queueCustomized: Boolean,
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
                queueCustomized = queueCustomized,
                currentQueuePosition = currentQueuePositionFor(currentSlotId, getCursor()),
            ),
        )
    }

    // --- song id replacement (a rescan of changed file content; see repointSavedQueue) ---

    @Query("UPDATE queue_items SET song_id = :newSongId WHERE song_id = :oldSongId")
    suspend fun repointQueueItems(oldSongId: String, newSongId: String): Int

    @Query("UPDATE queue_baseline_items SET song_id = :newSongId WHERE song_id = :oldSongId")
    suspend fun repointBaselineQueueItems(oldSongId: String, newSongId: String): Int

    /**
     * Moves every saved queue row of [oldSongId], live and baseline, to [newSongId], keeping each
     * row's slot id, position and extra tag, so the cursor and the saved order stay as they are.
     * [newSongId] must already have a song row (the foreign key). Runs inside the caller's
     * transaction when there is one.
     */
    @Transaction
    suspend fun repointSavedQueue(oldSongId: String, newSongId: String) {
        repointQueueItems(oldSongId, newSongId)
        repointBaselineQueueItems(oldSongId, newSongId)
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

/**
 * The queue position a cursor write stores for [currentSlotId], read inside the caller's transaction:
 * the position of its live row; while the slot is unchanged and its row is gone (taken by a song
 * deletion's cascade), the position [previous] holds, so a restore can still place the missing song;
 * null without a current slot or a known position.
 */
internal suspend fun QueueDao.currentQueuePositionFor(currentSlotId: String?, previous: PlaybackCursorEntity?): Int? {
    currentSlotId ?: return null
    return queuePositionOf(currentSlotId)
        ?: previous?.currentQueuePosition?.takeIf { previous.currentSlotId == currentSlotId }
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
