package com.v1per.synthx.data.scanner

import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFileWalkTest {
    private val root: File = Files.createTempDirectory("audio-walk").toFile()

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun graduationTempFilesAreNeverScannable() {
        assertFalse(isScannableAudioName(".graduating_Song.mp3"))
        assertFalse(isScannableAudioName(".graduating_Song (1).FLAC"))
        assertFalse(isScannableAudioName(null))
        assertFalse(isScannableAudioName("cover.jpg"))
        assertTrue(isScannableAudioName("Song.mp3"))
        assertTrue(isScannableAudioName("Song.M4A"))
        // Only the exact prefix is excluded — an ordinary dot-file or a similar name is not.
        assertTrue(isScannableAudioName(".hidden.mp3"))
        assertTrue(isScannableAudioName("graduating_Song.mp3"))
    }

    /** Walks a real directory tree through DocumentFile, not just the name predicate. */
    @Test fun walkSkipsGraduationTempFilesAtEveryDepth() = runTest {
        File(root, "Song.mp3").writeText("a")
        File(root, ".graduating_Song (1).mp3").writeText("partial")
        File(root, "notes.txt").writeText("x")
        val nested = File(root, "Artist/Album").also { it.mkdirs() }
        File(nested, "Track.flac").writeText("b")
        File(nested, ".graduating_Track.flac").writeText("partial")

        val names = collectAudioFiles(DocumentFile.fromFile(root)).mapNotNull { it.name }.sorted()

        assertEquals(listOf("Song.mp3", "Track.flac"), names)
    }
}
