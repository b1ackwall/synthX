package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("album_id")],
)
data class SongEntity(
    // SHA-256 of the first 64KB of the file, lowercase hex
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    val duration: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String?,
    val year: Int?,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    @ColumnInfo(name = "is_missing", defaultValue = "0") val isMissing: Boolean = false,
    @ColumnInfo(name = "missing_since") val missingSince: Long? = null,
    @ColumnInfo(defaultValue = "0") val verified: Boolean = false,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long? = null,
)
