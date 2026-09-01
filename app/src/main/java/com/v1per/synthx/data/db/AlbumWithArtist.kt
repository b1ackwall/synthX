package com.v1per.synthx.data.db

import androidx.room.ColumnInfo

// Query projection, not a Room entity
data class AlbumWithArtist(
    val id: String,
    val title: String,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String?,
    val artistName: String,
    val allSongsMissing: Boolean,
)
