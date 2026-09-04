package com.v1per.synthx.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongDeletionTest {
    private lateinit var artDir: File

    @BeforeTest fun setUp() {
        artDir = createTempDirectory("art_test").toFile()
    }

    @AfterTest fun tearDown() {
        artDir.deleteRecursively()
    }

    @Test fun compressedArtFileExists_trueWhenPresent() {
        File(artDir, "art_song1.jpg").writeText("x")
        assertTrue(compressedArtFileExists(artDir, "song1"))
    }

    @Test fun compressedArtFileExists_falseWhenOnlyFullResPresent() {
        File(artDir, "art_full_song1.png").writeText("x")
        assertFalse(compressedArtFileExists(artDir, "song1"))
    }

    @Test fun compressedArtFileExists_falseWhenOnlyAnotherSongsArtExists() {
        File(artDir, "art_othersong.jpg").writeText("x")
        assertFalse(compressedArtFileExists(artDir, "song1"))
    }

    @Test fun compressedArtFileExists_falseForEmptyDirectory() {
        assertFalse(compressedArtFileExists(artDir, "song1"))
    }

    @Test fun compressedArtFileExists_falseWhenArtDirectoryDoesNotExist() {
        val missingDir = File(artDir, "does_not_exist")
        assertFalse(compressedArtFileExists(missingDir, "song1"))
    }

    @Test fun fullResArtFileExists_trueWhenPresent() {
        File(artDir, "art_full_song1.png").writeText("x")
        assertTrue(fullResArtFileExists(artDir, "song1"))
    }

    @Test fun fullResArtFileExists_falseWhenOnlyCompressedPresent() {
        File(artDir, "art_song1.jpg").writeText("x")
        assertFalse(fullResArtFileExists(artDir, "song1"))
    }

    @Test fun fullResArtFileExists_falseWhenOnlyAnotherSongsArtExists() {
        File(artDir, "art_full_othersong.jpg").writeText("x")
        assertFalse(fullResArtFileExists(artDir, "song1"))
    }

    @Test fun fullResArtFileExists_falseForEmptyDirectory() {
        assertFalse(fullResArtFileExists(artDir, "song1"))
    }

    @Test fun fullResArtFileExists_falseWhenArtDirectoryDoesNotExist() {
        val missingDir = File(artDir, "does_not_exist")
        assertFalse(fullResArtFileExists(missingDir, "song1"))
    }

    // ------------------------------------------------------------------ artFilePresence
    // The single-listing index that replaces one directory scan per song in
    // ArtRecovery.findMissingArt's whole-library pass. Its whole reason to exist is being an
    // exact substitute for the two per-song helpers above, so most of what's worth asserting
    // is that it answers identically for every filename shape those helpers distinguish.

    @Test fun artFilePresence_findsBothVariants() {
        File(artDir, "art_song1.jpg").writeText("x")
        File(artDir, "art_full_song1.png").writeText("x")
        val presence = artFilePresence(artDir)
        assertTrue(presence.hasCompressed("song1"))
        assertTrue(presence.hasFullRes("song1"))
    }

    @Test fun artFilePresence_variantsAreIndependent() {
        File(artDir, "art_song1.jpg").writeText("x")
        File(artDir, "art_full_song2.webp").writeText("x")
        val presence = artFilePresence(artDir)
        assertTrue(presence.hasCompressed("song1"))
        assertFalse(presence.hasFullRes("song1"))
        assertFalse(presence.hasCompressed("song2"))
        assertTrue(presence.hasFullRes("song2"))
    }

    /** `art_full_x.jpg` is also a valid `art_` prefix — it must not register as compressed art
     *  for a song called "full_x", which is what a naive prefix strip would produce. */
    @Test fun artFilePresence_fullResFileNeverCountsAsCompressed() {
        File(artDir, "art_full_song1.jpg").writeText("x")
        val presence = artFilePresence(artDir)
        assertFalse(presence.hasCompressed("song1"))
        assertFalse(presence.hasCompressed("full_song1"))
        assertTrue(presence.hasFullRes("song1"))
    }

    /** compressedArtFileExists tests for exactly `art_<id>.jpg`; legacy .png must not satisfy it. */
    @Test fun artFilePresence_legacyPngIsNotCompressedArt() {
        File(artDir, "art_song1.png").writeText("x")
        assertFalse(artFilePresence(artDir).hasCompressed("song1"))
    }

    /** An interrupted write's `art_<id>.<uuid>.tmp` leftover is not a usable art file. */
    @Test fun artFilePresence_interruptedWriteLeftoverIsNotCompressedArt() {
        File(artDir, "art_song1.abc-123.tmp").writeText("x")
        assertFalse(artFilePresence(artDir).hasCompressed("song1"))
    }

    @Test fun artFilePresence_emptyDirectoryHasNothing() {
        val presence = artFilePresence(artDir)
        assertFalse(presence.hasCompressed("song1"))
        assertFalse(presence.hasFullRes("song1"))
    }

    @Test fun artFilePresence_missingDirectoryHasNothing() {
        val presence = artFilePresence(File(artDir, "does_not_exist"))
        assertFalse(presence.hasCompressed("song1"))
        assertFalse(presence.hasFullRes("song1"))
    }

    @Test fun artFilePresence_ignoresUnrelatedFiles() {
        File(artDir, "notes.txt").writeText("x")
        File(artDir, "artwork_song1.jpg").writeText("x")
        val presence = artFilePresence(artDir)
        assertFalse(presence.hasCompressed("song1"))
        assertFalse(presence.hasFullRes("song1"))
    }

    /**
     * The parity guarantee the O(N) rewrite rests on: over a directory holding every shape the
     * per-song helpers distinguish, the index must agree with them for every id, present or not.
     * If this ever fails, whole-library detection and single-song re-checks have drifted apart.
     */
    @Test fun artFilePresence_agreesWithPerSongHelpersAcrossEveryFilenameShape() {
        listOf(
            "art_compressed-only.jpg",
            "art_full_fullres-only.webp",
            "art_both.jpg",
            "art_full_both.png",
            "art_legacy.png",
            "art_partial.abc-123.tmp",
            "art_full_.jpg",
            "notes.txt",
        ).forEach { File(artDir, it).writeText("x") }

        val presence = artFilePresence(artDir)
        val ids = listOf(
            "compressed-only", "fullres-only", "both", "legacy", "partial",
            "", "notes", "full_both", "absent",
        )
        ids.forEach { id ->
            assertEquals(
                compressedArtFileExists(artDir, id),
                presence.hasCompressed(id),
                "compressed mismatch for id '$id'",
            )
            assertEquals(
                fullResArtFileExists(artDir, id),
                presence.hasFullRes(id),
                "full-res mismatch for id '$id'",
            )
        }
    }
}
