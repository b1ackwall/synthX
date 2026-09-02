package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per item in the persisted "baseline" order — the app-managed source-of-truth
 * sequence that survives shuffle toggles, distinct from `queue_items` (the live/active
 * order actually playing, which is shuffled order while shuffle is on).
 *
 * Structurally identical to [QueueItemEntity]: `slot_id` identifies the exact occurrence,
 * `position` is its mutable ordering key, and a deleted song CASCADEs its rows away.
 */
@Entity(
    tableName = "queue_baseline_items",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("position"), Index("song_id")],
)
data class BaselineQueueItemEntity(
    @PrimaryKey @ColumnInfo(name = "slot_id") val slotId: String,
    val position: Int,
    @ColumnInfo(name = "song_id") val songId: String,
)
