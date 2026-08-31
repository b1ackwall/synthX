package com.v1per.synthx.data.playlistseed

import java.util.UUID
import kotlinx.serialization.Serializable

/** Authoritative format identifiers for the Playlist Seed Manifest V1 contract. */
object PlaylistSeedManifestV1 {
    const val SCHEMA_VERSION: Int = 1
    const val HASH_STRATEGY: String = "synthx_sampled_sha256_v1"
}

/**
 * Single source of truth for "is this manifest acceptable" — shared by
 * [PlaylistSeedBootstrapper] (validating a manifest before seeding from it) and
 * [PlaylistSeedManifestGenerator] (self-checking a freshly-generated manifest before writing
 * it), so the two can never silently drift apart on what counts as valid.
 */
object PlaylistSeedManifestValidator {
    private val SONG_HASH_REGEX = Regex("^[0-9a-f]{64}$")

    fun isValid(manifest: PlaylistSeedManifest): Boolean {
        if (manifest.schemaVersion != PlaylistSeedManifestV1.SCHEMA_VERSION) return false
        if (manifest.hashStrategy != PlaylistSeedManifestV1.HASH_STRATEGY) return false

        val playlistIds = mutableSetOf<String>()
        for (playlist in manifest.playlists) {
            if (playlist.playlistId.isBlank()) return false
            if (!playlistIds.add(playlist.playlistId)) return false
            if (runCatching { UUID.fromString(playlist.playlistId) }.isFailure) return false
            if (playlist.name.isBlank()) return false
            if (playlist.songHashes.any { !SONG_HASH_REGEX.matches(it) }) return false
            if (playlist.songHashes.size != playlist.songHashes.toSet().size) return false
        }

        return true
    }
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
