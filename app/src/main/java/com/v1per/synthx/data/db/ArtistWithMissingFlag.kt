package com.v1per.synthx.data.db

// Query projection, not a Room entity — see AlbumWithArtist.kt for the album-side equivalent.
data class ArtistWithMissingFlag(
    val id: String,
    val name: String,
    val allSongsMissing: Boolean,
)
