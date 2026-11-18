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
 *
 * `extra_kind` and `extra_sequence` are set on a session extra only, a slot added with Play Next
 * or Add to Queue while shuffle is on (see [com.v1per.synthx.data.repository.QueueExtraTag]), and
 * are NULL on every ordinary slot.
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
    @ColumnInfo(name = "extra_kind") val extraKind: Int? = null,
    @ColumnInfo(name = "extra_sequence") val extraSequence: Long? = null,
)
