package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding "where playback was" — the raw timeline index into `queue_items`
 * and the position within it, plus the shuffle/repeat mode the queue was in. Previously lived
 * in SharedPreferences, written independently of `queue_items`; moved into Room so
 * [QueueDao.replaceQueueAndCursor] can commit both in one transaction, closing the window
 * where a process death between two independent writes left the cursor pointing at a queue
 * structure that was never actually persisted (or vice versa). [shuffleEnabled]/[repeatMode]
 * joined this table for the same reason: a restore installs a whole database file, so state
 * that lives outside it (SharedPreferences) cannot travel atomically with the queue it
 * describes — see data 2.4.
 *
 * `id` is always 0 — there is only ever one cursor, so this is an upsert target rather than a
 * growing table. No foreign key to `songs`/`queue_items`: [queueIndex] is a raw position, not a
 * song reference (see [QueuePersistence.RestoredQueue] for how it's resolved back to a song).
 */
@Entity(tableName = "playback_cursor")
data class PlaybackCursorEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "queue_index") val queueIndex: Int,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "shuffle_enabled", defaultValue = "0") val shuffleEnabled: Boolean = false,
    /** media3's Player.REPEAT_MODE_* int, stored raw so no mapping is needed at either end. */
    @ColumnInfo(name = "repeat_mode", defaultValue = "0") val repeatMode: Int = 0,
)
