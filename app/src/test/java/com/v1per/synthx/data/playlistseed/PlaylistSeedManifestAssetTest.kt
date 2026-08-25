package com.v1per.synthx.data.playlistseed

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PlaylistSeedManifestAssetTest {

    @Test
    fun assetDeserializesAsV1Manifest() {
        assertEquals(PlaylistSeedManifestV1.SCHEMA_VERSION, approvedManifest.schemaVersion)
        assertEquals(PlaylistSeedManifestV1.HASH_STRATEGY, approvedManifest.hashStrategy)
    }

    @Test
    fun approvedManifestHasExpectedCanonicalCounts() {
        val allSongHashes = approvedManifest.playlists.flatMap { it.songHashes }
        val playlistReferenceCounts = mutableMapOf<String, Int>()
        approvedManifest.playlists.forEach { playlist ->
            playlist.songHashes.toSet().forEach { songHash ->
                playlistReferenceCounts.merge(songHash, 1, Int::plus)
            }
        }

        assertEquals(EXPECTED_PLAYLIST_COUNT, approvedManifest.playlists.size)
        assertEquals(EXPECTED_MEMBERSHIP_COUNT, allSongHashes.size)
        assertEquals(EXPECTED_UNIQUE_SONG_HASH_COUNT, allSongHashes.toSet().size)
        assertEquals(
            EXPECTED_REUSED_SONG_HASH_COUNT,
            playlistReferenceCounts.values.count { it > 1 },
        )
    }

    @Test
    fun playlistIdsAreUniqueValidUuidV4() {
        val playlistIds = approvedManifest.playlists.map { it.playlistId }
        assertEquals(playlistIds.size, playlistIds.toSet().size, "Playlist IDs must be unique")

        playlistIds.forEach { playlistId ->
            val uuid = try {
                UUID.fromString(playlistId)
            } catch (error: IllegalArgumentException) {
                fail("Playlist ID is not a valid UUID: $playlistId")
            }

            assertTrue(
                playlistId.equals(uuid.toString(), ignoreCase = true),
                "Playlist ID is not in canonical UUID form: $playlistId",
            )
            assertEquals(4, uuid.version(), "Playlist ID is not UUID v4: $playlistId")
        }
    }

    @Test
    fun playlistNamesAreUniqueAndNonBlank() {
        val playlistNames = approvedManifest.playlists.map { it.name }
        playlistNames.forEach { name ->
            assertTrue(name.isNotBlank(), "Playlist names must not be blank")
        }
        assertEquals(playlistNames.size, playlistNames.toSet().size, "Playlist names must be unique")
    }

    @Test
    fun songHashesAreValid() {
        approvedManifest.playlists.forEach { playlist ->
            assertTrue(
                playlist.songHashes.isNotEmpty(),
                "Playlist ${playlist.playlistId} must contain at least one song hash",
            )
            playlist.songHashes.forEach { songHash ->
                assertTrue(
                    SONG_HASH_REGEX.matches(songHash),
                    "Invalid song hash in playlist ${playlist.playlistId}: $songHash",
                )
            }
        }
    }

    @Test
    fun noPlaylistContainsDuplicateSongHashes() {
        approvedManifest.playlists.forEach { playlist ->
            assertEquals(
                playlist.songHashes.size,
                playlist.songHashes.toSet().size,
                "Playlist ${playlist.playlistId} contains a duplicate song hash",
            )
        }
    }

    @Test
    fun approvedManifestRawBytesMatchLockedSha256() {
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(approvedManifestBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals(EXPECTED_RAW_SHA256, actualSha256)
    }

    private companion object {
        const val EXPECTED_PLAYLIST_COUNT = 68
        const val EXPECTED_MEMBERSHIP_COUNT = 1_934
        const val EXPECTED_UNIQUE_SONG_HASH_COUNT = 1_405
        const val EXPECTED_REUSED_SONG_HASH_COUNT = 414
        const val EXPECTED_RAW_SHA256 =
            "daac4516f0b6e82e528198418bbc9d18cf517496134dd61e89526032f6ca9c09"

        val SONG_HASH_REGEX = Regex("^[0-9a-f]{64}$")
        val approvedManifestBytes: ByteArray by lazy {
            Files.readAllBytes(resolveAssetPath())
        }
        val approvedManifest: PlaylistSeedManifest by lazy {
            Json.decodeFromString(
                PlaylistSeedManifest.serializer(),
                approvedManifestBytes.decodeToString(),
            )
        }

        fun resolveAssetPath(): Path {
            val moduleRelativePath = Path.of(
                "src",
                "main",
                "assets",
                "playlist_seed_manifest_v1.json",
            )
            val candidates = listOf(moduleRelativePath, Path.of("app").resolve(moduleRelativePath))
            return candidates.firstOrNull { Files.isRegularFile(it) }
                ?: error(
                    "Canonical playlist seed asset not found from working directory " +
                        System.getProperty("user.dir"),
                )
        }
    }
}
