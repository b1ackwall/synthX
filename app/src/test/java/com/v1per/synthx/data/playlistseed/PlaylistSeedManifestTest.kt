package com.v1per.synthx.data.playlistseed

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistSeedManifestTest {

    private val json = Json

    @Test
    fun representativeManifest_roundTripsAndPreservesOrdering() {
        val manifest = PlaylistSeedManifest(
            schemaVersion = PlaylistSeedManifestV1.SCHEMA_VERSION,
            hashStrategy = PlaylistSeedManifestV1.HASH_STRATEGY,
            playlists = listOf(
                PlaylistSeedPlaylist(
                    playlistId = "playlist-2",
                    name = "Later Playlist",
                    songHashes = listOf("hash-b", "hash-a"),
                ),
                PlaylistSeedPlaylist(
                    playlistId = "playlist-1",
                    name = "Earlier Playlist",
                    songHashes = listOf("hash-c"),
                ),
            ),
        )

        val encoded = json.encodeToString(PlaylistSeedManifest.serializer(), manifest)
        val decoded = json.decodeFromString(PlaylistSeedManifest.serializer(), encoded)

        assertEquals(manifest, decoded)
        assertEquals(listOf("playlist-2", "playlist-1"), decoded.playlists.map { it.playlistId })
        assertEquals(listOf("hash-b", "hash-a"), decoded.playlists.first().songHashes)
    }

    @Test
    fun contractAllowsSharedHashesDuplicateNamesAndEmptyMembership() {
        val sharedHash = "shared-hash"
        val manifest = PlaylistSeedManifest(
            schemaVersion = PlaylistSeedManifestV1.SCHEMA_VERSION,
            hashStrategy = PlaylistSeedManifestV1.HASH_STRATEGY,
            playlists = listOf(
                PlaylistSeedPlaylist(
                    playlistId = "playlist-a",
                    name = "Same Name",
                    songHashes = listOf(sharedHash),
                ),
                PlaylistSeedPlaylist(
                    playlistId = "playlist-b",
                    name = "Same Name",
                    songHashes = listOf(sharedHash),
                ),
                PlaylistSeedPlaylist(
                    playlistId = "playlist-empty",
                    name = "Empty Playlist",
                    songHashes = emptyList(),
                ),
            ),
        )

        val decoded = json.decodeFromString(
            PlaylistSeedManifest.serializer(),
            json.encodeToString(PlaylistSeedManifest.serializer(), manifest),
        )

        assertEquals("Same Name", decoded.playlists[0].name)
        assertEquals("Same Name", decoded.playlists[1].name)
        assertEquals(listOf("playlist-a", "playlist-b"), decoded.playlists.take(2).map { it.playlistId })
        assertEquals(sharedHash, decoded.playlists[0].songHashes.single())
        assertEquals(sharedHash, decoded.playlists[1].songHashes.single())
        assertEquals(emptyList(), decoded.playlists[2].songHashes)
    }

    @Test
    fun v1ConstantsHaveCanonicalValues() {
        assertEquals(1, PlaylistSeedManifestV1.SCHEMA_VERSION)
        assertEquals("synthx_sampled_sha256_v1", PlaylistSeedManifestV1.HASH_STRATEGY)
    }
}
