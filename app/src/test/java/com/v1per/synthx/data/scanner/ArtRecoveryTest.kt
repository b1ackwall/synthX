package com.v1per.synthx.data.scanner

import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtRecoveryTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private fun song(
        id: String,
        isMissing: Boolean = false,
        albumArtUri: String? = "content://fake-art/$id",
        albumArtFullUri: String? = "content://fake-art-full/$id",
    ) = SongEntity(
        id = id,
        title = "Title $id",
        albumId = null,
        duration = 1000L,
        filePath = "content://fake/$id",
        albumArtUri = albumArtUri,
        albumArtFullUri = albumArtFullUri,
        year = null,
        dateAdded = 0L,
        isMissing = isMissing,
    )

    @Test fun excludesSongsWhoseArtExists() {
        val songs = listOf(song("1"), song("2"))
        val result = ArtRecovery.findMissingArt(songs, compressedExists = { id -> id == "1" }, fullResExists = { true })
        assertEquals(listOf(song("2")), result)
    }

    @Test fun excludesAlreadyMissingSongsRegardlessOfArtState() {
        val songs = listOf(song("1", isMissing = true))
        val result = ArtRecovery.findMissingArt(songs, compressedExists = { false }, fullResExists = { false })
        assertEquals(emptyList(), result)
    }

    @Test fun includesSongsWhoseArtIsGone() {
        val songs = listOf(song("1"))
        val result = ArtRecovery.findMissingArt(songs, compressedExists = { false }, fullResExists = { false })
        assertEquals(songs, result)
    }

    @Test fun emptyInputProducesEmptyOutput() {
        assertEquals(
            emptyList(),
            ArtRecovery.findMissingArt(emptyList(), compressedExists = { true }, fullResExists = { true }),
        )
    }

    @Test fun mixOfPresentMissingAndAlreadyFlaggedSongs() {
        val songs = listOf(
            song("has-art"),
            song("no-art"),
            song("missing-audio", isMissing = true),
        )
        val result = ArtRecovery.findMissingArt(
            songs,
            compressedExists = { id -> id == "has-art" },
            fullResExists = { id -> id == "has-art" },
        )
        assertEquals(listOf(song("no-art")), result)
    }

    @Test fun flagsSongWhenOnlyCompressedIsMissing() {
        // Full-res still on disk, compressed gone — must be flagged even though SOME art file
        // for this song exists, per the bug this test guards against.
        val songs = listOf(song("1"))
        val result = ArtRecovery.findMissingArt(songs, compressedExists = { false }, fullResExists = { true })
        assertEquals(songs, result)
    }

    @Test fun flagsSongWhenOnlyFullResIsMissing() {
        val songs = listOf(song("1"))
        val result = ArtRecovery.findMissingArt(songs, compressedExists = { true }, fullResExists = { false })
        assertEquals(songs, result)
    }

    @Test fun doesNotFlagSongWhoseMissingVariantWasNeverProducedToBeginWith() {
        // albumArtUri null: this song never had a compressed thumbnail (a legitimate partial
        // original extraction outcome) — the compressed predicate must never even be consulted,
        // and the song must not be flagged just because a compressed file doesn't exist.
        val songs = listOf(song("1", albumArtUri = null))
        val result = ArtRecovery.findMissingArt(songs, compressedExists = { false }, fullResExists = { true })
        assertEquals(emptyList(), result)
    }

    @Test fun songWithNoArtFieldsAtAllIsNeverFlagged() {
        val songs = listOf(song("1", albumArtUri = null, albumArtFullUri = null))
        val result = ArtRecovery.findMissingArt(songs, compressedExists = { false }, fullResExists = { false })
        assertEquals(emptyList(), result)
    }

    // mergeArtRecoveryResult: the bug-2 regression guard — a fresh extraction attempt returning
    // null for one variant must never overwrite that field's still-good existing value, and a
    // successful variant must still apply.

    @Test fun mergeAppliesBothFreshUrisWhenBothSucceed() {
        val current = song("1", albumArtUri = "old-compressed", albumArtFullUri = "old-full")
        val result = ArtRecovery.mergeArtRecoveryResult(current, "new-compressed", "new-full")
        assertEquals("new-compressed", result.albumArtUri)
        assertEquals("new-full", result.albumArtFullUri)
    }

    @Test fun mergePreservesExistingCompressedWhenFreshCompressedFails() {
        // Full-res re-extraction succeeds, compressed fails again — the still-good compressed
        // value must survive, not be nulled out.
        val current = song("1", albumArtUri = "old-compressed", albumArtFullUri = "old-full")
        val result = ArtRecovery.mergeArtRecoveryResult(current, compressedUri = null, fullArtUri = "new-full")
        assertEquals("old-compressed", result.albumArtUri)
        assertEquals("new-full", result.albumArtFullUri)
    }

    @Test fun mergePreservesExistingFullResWhenFreshFullResFails() {
        val current = song("1", albumArtUri = "old-compressed", albumArtFullUri = "old-full")
        val result = ArtRecovery.mergeArtRecoveryResult(current, compressedUri = "new-compressed", fullArtUri = null)
        assertEquals("new-compressed", result.albumArtUri)
        assertEquals("old-full", result.albumArtFullUri)
    }

    @Test fun mergeLeavesBothNullWhenNeitherExistedAndBothFail() {
        val current = song("1", albumArtUri = null, albumArtFullUri = null)
        val result = ArtRecovery.mergeArtRecoveryResult(current, compressedUri = null, fullArtUri = null)
        assertEquals(null, result.albumArtUri)
        assertEquals(null, result.albumArtFullUri)
    }
}
