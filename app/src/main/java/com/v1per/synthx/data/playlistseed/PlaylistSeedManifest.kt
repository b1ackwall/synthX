package com.v1per.synthx.data.playlistseed

import kotlinx.serialization.Serializable

/** Authoritative format identifiers for the Playlist Seed Manifest V1 contract. */
object PlaylistSeedManifestV1 {
    const val SCHEMA_VERSION: Int = 1
    const val HASH_STRATEGY: String = "synthx_sampled_sha256_v1"
}

/**
 * Frozen playlist reconstruction data, independent of the live playlist backup/export model.
 */
@Serializable
data class PlaylistSeedManifest(
    val schemaVersion: Int,
    val hashStrategy: String,
    val playlists: List<PlaylistSeedPlaylist>,
)

/** A stable playlist identity and its ordered synthX song IDs. */
@Serializable
data class PlaylistSeedPlaylist(
    val playlistId: String,
    val name: String,
    val songHashes: List<String>,
)
