package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per item in the persisted playback queue, in timeline order.
 *
 * `slot_id` is the stable identity of this exact queue occurrence; `position` is only its
 * current ordering key and changes whenever the queue is rewritten. Deleting a song CASCADEs
 * every slot that references it, so restore still tolerates a shorter queue.
 */
@Entity(
    tableName = "queue_items",
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
data class QueueItemEntity(
    @PrimaryKey @ColumnInfo(name = "slot_id") val slotId: String,
    val position: Int,
    @ColumnInfo(name = "song_id") val songId: String,
)
