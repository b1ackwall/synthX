package com.v1per.synthx.data.scanner

import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.file.Files
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFileWalkTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

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

        val query = DirectoryChildrenQuery { directory ->
            DirectoryListing(
                directory.listFiles().map { child ->
                    DirectoryChild(child, child.name, child.isDirectory)
                },
                complete = true,
            )
        }
        val names = collectAudioFiles(DocumentFile.fromFile(root), query)
            .files.mapNotNull { it.name }.sorted()

        assertEquals(listOf("Song.mp3", "Track.flac"), names)
    }

    @Test fun midWalkFailureMarksWalkIncompleteAndKeepsFilesAlreadyFound() = runTest {
        val rootSong = File(root, "Root.mp3").also { it.writeText("root") }
        val nested = File(root, "Artist").also { it.mkdirs() }
        val nestedSong = File(nested, "Nested.flac").also { it.writeText("nested") }
        val broken = File(nested, "Broken").also { it.mkdirs() }

        val query = DirectoryChildrenQuery { directory ->
            if (directory.name == broken.name) {
                DirectoryListing(emptyList(), complete = false)
            } else {
                DirectoryListing(
                    directory.listFiles().map { child ->
                        DirectoryChild(child, child.name, child.isDirectory)
                    },
                    complete = true,
                )
            }
        }

        val walk = collectAudioFiles(DocumentFile.fromFile(root), query)

        assertFalse(walk.complete)
        assertEquals(
            setOf(rootSong.name, nestedSong.name),
            walk.files.mapNotNull { it.name }.toSet(),
        )
    }
}
