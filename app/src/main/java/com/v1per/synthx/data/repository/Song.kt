package com.v1per.synthx.data.repository

import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: String,
    val title: String,
    /** Display string: every artist, comma-joined in song_artists.position order. */
    val artist: String,
    val album: String,
    val duration: Long,
    val filePath: String,
    val albumArtUri: String? = null,
    /** Untouched-original-bytes full-resolution art, used by the player sheet. */
    val albumArtFullUri: String? = null,
    val isMissing: Boolean = false,
    val dateAdded: Long = 0L,
    /** Null when the file carried no album tag. Needed to navigate to album detail. */
    val albumId: String? = null,
    /** Individual artists in position order — [artist] is these joined for display. */
    val artists: List<Artist> = emptyList(),
)
