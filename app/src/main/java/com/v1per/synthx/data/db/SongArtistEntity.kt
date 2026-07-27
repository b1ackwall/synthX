package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "song_artists",
    primaryKeys = ["song_id", "artist_id"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artist_id")],
)
data class SongArtistEntity(
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "artist_id") val artistId: String,
    val position: Int,
)
