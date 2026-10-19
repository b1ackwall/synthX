package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.scanner.ScanTestFixture.Companion.bytes
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Song identity ([HashUtils.computeSongId]) on real files. The id is stored in every song,
 * playlist entry and queue row, so its exact recipe is pinned: SHA-256 of the first 64 KB, the
 * last 64 KB only when the file is larger than 128 KB, then the size as 8 bytes big-endian.
 */
@RunWith(AndroidJUnit4::class)
class SongFingerprintInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(context.cacheDir, "fingerprint-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun emptyFileHasNoId() {
        assertNull(idOf(ByteArray(0)))
    }

    @Test fun idFollowsThePinnedRecipeAtEverySizeBoundary() {
        for (size in listOf(1, 1_000, SAMPLE, SAMPLE + 1, 100_000, 2 * SAMPLE, 2 * SAMPLE + 1, 300_000)) {
            val content = bytes(size, seed = size)
            assertEquals("size $size", recipe(content), idOf(content))
        }
    }

    @Test fun identicalBytesGiveTheSameIdWhateverTheFileName() {
        val content = bytes(200_000, seed = 7)

        assertEquals(idOf(content, "one.mp3"), idOf(content, "two.flac"))
    }

    @Test fun largeFilesWithTheSameHeadButADifferentTailDiffer() {
        val a = bytes(300_000, seed = 1)
        val b = a.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertNotEquals(idOf(a), idOf(b))
    }

    @Test fun largeFilesWithTheSameHeadAndTailButADifferentSizeDiffer() {
        val head = bytes(SAMPLE, seed = 1)
        val tail = bytes(SAMPLE, seed = 2)
        val shorter = head + ByteArray(10_000) + tail
        val longer = head + ByteArray(10_001) + tail

        assertNotEquals(idOf(shorter), idOf(longer))
    }

    @Test fun middleBytesOfALargeFileDoNotChangeTheId() {
        val a = bytes(300_000, seed = 1)
        val b = a.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }

        assertEquals(idOf(a), idOf(b))
    }

    @Test fun smallFilesDifferingInTheirLastByteDiffer() {
        val a = bytes(50_000, seed = 1)
        val b = a.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertNotEquals(idOf(a), idOf(b))
    }

    /**
     * Pinned current behaviour (suspected bug, reproduced): between 64 KB and 128 KB only the
     * first 64 KB and the size are hashed, so two same-size files that differ after 64 KB share
     * one id and would collapse into one song.
     */
    @Test fun pinnedMidSizeFilesDifferingOnlyAfter64KbShareAnId() {
        val a = bytes(100_000, seed = 1)
        val b = a.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertEquals("pinned: bytes past 64 KB ignored below 128 KB", idOf(a), idOf(b))
    }

    private fun idOf(content: ByteArray, name: String = "f-${System.nanoTime()}.mp3"): String? {
        val file = File(dir, name).also { it.writeBytes(content) }
        return HashUtils.computeSongId(DocumentFile.fromFile(file), context)
    }

    private fun recipe(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(content, 0, minOf(SAMPLE, content.size))
        if (content.size > 2 * SAMPLE) digest.update(content, content.size - SAMPLE, SAMPLE)
        val size = content.size.toLong()
        digest.update(ByteArray(8) { i -> (size shr (8 * (7 - i))).toByte() })
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SAMPLE = 65_536
    }
}
