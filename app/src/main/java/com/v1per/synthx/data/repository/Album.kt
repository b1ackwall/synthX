package com.v1per.synthx.data.repository

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val albumArtUri: String?,
    val allSongsMissing: Boolean = false,
)
