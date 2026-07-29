package com.v1per.synthx.data.repository

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val filePath: String,
    val albumArtUri: String? = null,
    val isMissing: Boolean = false,
    val dateAdded: Long = 0L,
)
