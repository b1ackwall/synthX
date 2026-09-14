package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    indices = [Index(value = ["title"])],
)
data class AlbumEntity(
    // UUID
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,
    val year: Int?,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String?,
)
