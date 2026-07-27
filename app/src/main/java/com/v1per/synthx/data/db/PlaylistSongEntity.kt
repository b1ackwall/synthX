package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlist_id", "song_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("song_id")],
)
data class PlaylistSongEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
