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
 * Structurally identical to [QueueItemEntity]: `position` is the primary key for the same
 * reason (a single global list, always rewritten wholesale, position doubles as ORDER BY
 * key), and a deleted song CASCADEs its row away the same way.
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
    indices = [Index("song_id")],
)
data class BaselineQueueItemEntity(
    @PrimaryKey val position: Int,
    @ColumnInfo(name = "song_id") val songId: String,
)
