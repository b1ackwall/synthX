package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding "where playback was" — the stable slot id within `queue_items`
 * and the playback position within it, plus the shuffle/repeat mode the queue was in. Lives in
 * Room, not SharedPreferences, so [QueueDao.replaceQueueAndCursor] can commit cursor and queue
 * in one transaction, closing the window where a process death between two independent writes
 * leaves the cursor pointing at a queue structure that was never actually persisted (or vice
 * versa). [shuffleEnabled]/[repeatMode] are here for the same reason: a restore installs a
 * whole database file, so state that lives outside it (SharedPreferences) cannot travel
 * atomically with the queue it describes.
 *
 * `id` is always 0 — there is only ever one cursor, so this is an upsert target rather than a
 * growing table. No foreign key to `queue_items`: [currentSlotId] may temporarily name a slot
 * that was removed by a song deletion; restore handles that by falling back to the first slot.
 */
@Entity(tableName = "playback_cursor")
data class PlaybackCursorEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "current_slot_id") val currentSlotId: String?,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "shuffle_enabled", defaultValue = "0") val shuffleEnabled: Boolean = false,
    /** media3's Player.REPEAT_MODE_* int, stored raw so no mapping is needed at either end. */
    @ColumnInfo(name = "repeat_mode", defaultValue = "0") val repeatMode: Int = 0,
)
