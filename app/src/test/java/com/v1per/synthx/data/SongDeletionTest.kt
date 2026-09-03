package com.v1per.synthx.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
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

    @Test fun trueWhenCompressedArtFileExists() {
        File(artDir, "art_song1.jpg").writeText("x")
        assertTrue(songHasArtFile(artDir, "song1"))
    }

    @Test fun trueWhenOnlyFullResArtFileExists() {
        File(artDir, "art_full_song1.png").writeText("x")
        assertTrue(songHasArtFile(artDir, "song1"))
    }

    @Test fun falseWhenOnlyAnotherSongsArtExists() {
        File(artDir, "art_othersong.jpg").writeText("x")
        assertFalse(songHasArtFile(artDir, "song1"))
    }

    @Test fun falseForEmptyDirectory() {
        assertFalse(songHasArtFile(artDir, "song1"))
    }

    @Test fun falseWhenArtDirectoryDoesNotExist() {
        val missingDir = File(artDir, "does_not_exist")
        assertFalse(songHasArtFile(missingDir, "song1"))
    }

    @Test fun matchesInterruptedTempWriteByIdPrefix() {
        File(artDir, "art_song1.${"abc-uuid"}.tmp").writeText("x")
        assertTrue(songHasArtFile(artDir, "song1"))
    }
}
