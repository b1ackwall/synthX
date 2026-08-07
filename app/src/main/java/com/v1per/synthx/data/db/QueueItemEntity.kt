package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per item in the persisted playback queue, in timeline order.
 *
 * `position` is the primary key rather than a generated id: the queue is a single global
 * list that is always rewritten wholesale, so position uniquely identifies a row and
 * doubles as the ORDER BY key. Deleting a song CASCADEs its queue row away, which is why
 * the restore path has to tolerate a queue that came back shorter than it was saved.
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
    indices = [Index("song_id")],
)
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    @ColumnInfo(name = "song_id") val songId: String,
)
