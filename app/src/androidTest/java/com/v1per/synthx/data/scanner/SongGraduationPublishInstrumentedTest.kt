package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.documentfile.provider.CollidingRenameDocumentFile
import androidx.documentfile.provider.DecoyLookupDocumentFile
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.FixedTypeDocumentFile
import androidx.documentfile.provider.RefusingDeleteDocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [copyToGraduationTemp] / [verifyAndPublishGraduationTemp] / [copyVerifyAndPublish] —
 * graduation's copy -> verify -> rename -> (no `findFile`) sequence in isolation from the
 * coordination machinery already covered by [SongGraduationCoordinationInstrumentedTest].
 *
 * Follows [com.v1per.synthx.data.backup.SafBackupPublisherInstrumentedTest]'s established
 * pattern: `DocumentFile.fromFile` over a real filesystem directory (a `RawDocumentFile`), not a
 * mocked or synthetic tree-URI SAF provider.
 *
 * **Honest coverage limits.** This is real byte-level I/O and real `DocumentFile.renameTo` /
 * `findFile` semantics against actual files on disk — meaningfully more real than a pure mock —
 * but it is NOT a true tree-URI SAF content provider:
 *  - `RawDocumentFile.renameTo` mutates its own backing `File` to the renamed target on success,
 *    the same continuity `TreeDocumentFile.renameTo` provides for a real SAF tree. On a plain
 *    filesystem there is one document per name, so [successfulMovePublishesTheVerifiedTempDocumentDirectly]
 *    publishes into a [DecoyLookupDocumentFile] instead: once the final name exists, its
 *    `findFile` returns a different document, standing in for a SAF provider whose listing lags
 *    the rename. Code that re-resolves the published document by name (the bug F13 fixed)
 *    publishes the decoy and fails that test.
 *  - Collision-at-rename-time is simulated directly (a `DocumentFile` whose `renameTo` returns
 *    false because a real file already occupies the target name), not via a genuine
 *    check-then-act race against a second concurrent actor — reproducing the actual race
 *    reliably in a synchronous JVM test isn't practical, per the task's own note.
 */
@RunWith(AndroidJUnit4::class)
class SongGraduationPublishInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun successfulMovePublishesTheVerifiedTempDocumentDirectly() {
        val (sourceDir, destDir) = twoRoots()
        val bytes = ByteArray(4096) { (it * 7).toByte() }
        val source = writeSourceFile(sourceDir, "Song.dat", bytes)
        // Once Song.dat exists, a by-name lookup returns this other file instead.
        val decoy = File(newRoot("decoy"), "Decoy.dat").also { it.writeBytes(byteArrayOf(6, 6, 6)) }
        val destinationRoot = DecoyLookupDocumentFile(destDir, "Song.dat", decoy)
        val songId = checkNotNull(HashUtils.computeSongId(source, context))

        val result = runCopyVerifyAndPublishBlocking(source, destinationRoot, "Song.dat", songId)

        val published = assertPublished(result)
        assertEquals("Song.dat", published.name)
        assertEquals(DocumentFile.fromFile(File(destDir, "Song.dat")).uri, published.uri)
        assertArrayEquals(bytes, File(destDir, "Song.dat").readBytes())
        // No leftover temp document — the published doc IS the one that was copied and verified,
        // not a fresh lookup for a second document.
        assertEquals(listOf("Song.dat"), destDir.list()?.toList())
    }

    @Test
    fun middleCorruptionOutsideTheSampledRegionIsMissedBySampledIdentityButCaughtByFullContentCheck() {
        val (sourceDir, destDir) = twoRoots()
        // Larger than 2x the 64KB sample size so computeSongId's tail sample doesn't overlap the
        // head sample, leaving a genuine untouched middle region.
        val size = 65536 * 3
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val source = writeSourceFile(sourceDir, "Album.dat", bytes)
        val destinationRoot = DocumentFile.fromFile(destDir)
        val songId = checkNotNull(HashUtils.computeSongId(source, context))

        val copyOutcome = copyToGraduationTemp(context, source, destinationRoot, "Album.dat")
        val copied = copyOutcome as? GraduationCopyOutcome.Copied
            ?: error("Expected a successful copy, got $copyOutcome")

        // Simulate a provider that persisted something other than what it was handed: corrupt a
        // single byte squarely in the middle, far from both 64KB sample windows.
        corruptByteAt(copied.temp, size / 2)

        // The sampled identity check alone does not see this corruption.
        assertEquals(songId, HashUtils.computeSongId(copied.temp, context))

        val result = verifyAndPublishGraduationTemp(
            context, destinationRoot, copied.temp, copied.finalName, songId, copied.sourceDigest,
        )

        val failed = result as? GraduationPublishResult.Failed
            ?: error("Expected the full-content check to reject the corrupted copy, got $result")
        assertEquals("Copied file failed full-content verification", failed.reason)
        // Aborted before publication: nothing was renamed to the final name, and the corrupted
        // temp document was cleaned up rather than left behind or promoted.
        assertFalse(File(destDir, "Album.dat").exists())
        assertTrue(destDir.list().orEmpty().none { it == copied.temp.name })
        // The original source is completely untouched by this function — graduateAdmitted only
        // calls source.delete() after receiving GraduationPublishResult.Published, which this
        // corruption never reaches.
        assertArrayEquals(bytes, File(sourceDir, "Album.dat").readBytes())
    }

    @Test
    fun renameCollisionProducesADistinguishableFailureAndNeverPublishesTheWrongDocument() {
        val (sourceDir, destDir) = twoRoots()
        val bytes = ByteArray(1024) { it.toByte() }
        val source = writeSourceFile(sourceDir, "Track.dat", bytes)
        val destinationRoot = CollidingRenameDocumentFile(destDir, "Track.dat")
        val songId = checkNotNull(HashUtils.computeSongId(source, context))

        // The destination directory is empty at check time, so resolveCollisionFreeName picks
        // the plain name — the collision below lands strictly between that check and the rename.
        val copyOutcome = copyToGraduationTemp(context, source, destinationRoot, "Track.dat")
        val copied = copyOutcome as? GraduationCopyOutcome.Copied
            ?: error("Expected a successful copy, got $copyOutcome")
        assertEquals("Track.dat", copied.finalName)

        // Simulate another writer landing on the target name after resolveCollisionFreeName's
        // check but before this rename — the TOCTOU race the collision-message fix targets.
        val occupant = byteArrayOf(9, 9, 9)
        File(destDir, "Track.dat").writeBytes(occupant)

        val result = verifyAndPublishGraduationTemp(
            context, destinationRoot, copied.temp, copied.finalName, songId, copied.sourceDigest,
        )

        val failed = result as? GraduationPublishResult.Failed
            ?: error("Expected the rename collision to be reported as a failure, got $result")
        assertTrue(
            "Expected a collision-specific message, got: ${failed.reason}",
            failed.reason.contains("another file named"),
        )
        // The pre-existing occupant is untouched — no wrong document got published over it.
        assertArrayEquals(occupant, File(destDir, "Track.dat").readBytes())
        assertArrayEquals(bytes, File(sourceDir, "Track.dat").readBytes())
    }

    @Test
    fun tempCleanupRefusalIsCarriedInTheFailureReason() {
        val (sourceDir, destDir) = twoRoots()
        val bytes = ByteArray(65536 * 3) { (it % 251).toByte() }
        val source = writeSourceFile(sourceDir, "Refusal.dat", bytes)
        val destinationRoot = DocumentFile.fromFile(destDir)
        val songId = checkNotNull(HashUtils.computeSongId(source, context))
        val copied = copyToGraduationTemp(context, source, destinationRoot, "Refusal.dat")
            as GraduationCopyOutcome.Copied
        corruptByteAt(copied.temp, bytes.size / 2)

        val result = verifyAndPublishGraduationTemp(
            context,
            destinationRoot,
            RefusingDeleteDocumentFile(copied.temp),
            copied.finalName,
            songId,
            copied.sourceDigest,
        )

        val failed = result as GraduationPublishResult.Failed
        assertTrue(failed.reason.contains("temporary destination file may remain"))
        assertTrue(File(destDir, copied.temp.name!!).exists())
    }

    @Test
    fun publishedCleanupRefusalWarnsThatAScannableCopyMayRemain() {
        val destDir = newRoot("published-cleanup-refusal")
        val file = File(destDir, "Published.mp3").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val published = RefusingDeleteDocumentFile(DocumentFile.fromFile(file))

        val reason = reasonAfterPublishedCleanup(
            "Copy verified, but the original file could not be removed.",
            published,
        )

        assertTrue(reason.contains("copied file may remain in your Music folder"))
        assertTrue(reason.contains("later library scan"))
        assertTrue(file.exists())
    }

    @Test
    fun cancellationDuringCopyCleansThePartialTempBeforePropagating() {
        val (sourceDir, destDir) = twoRoots()
        val source = writeSourceFile(sourceDir, "Cancelled.dat", byteArrayOf(1, 2, 3))

        var cancellationPropagated = false
        try {
            copyToGraduationTemp(
                context,
                source,
                DocumentFile.fromFile(destDir),
                "Cancelled.dat",
            ) { _, destinationUri ->
                File(requireNotNull(destinationUri.path)).writeBytes(byteArrayOf(1))
                throw CancellationException("test cancellation")
            }
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        assertTrue(destDir.list().orEmpty().isEmpty())
    }

    private fun twoRoots(): Pair<File, File> {
        val source = newRoot("source")
        val dest = newRoot("dest")
        return source to dest
    }

    private fun newRoot(label: String): File {
        val root = File(context.cacheDir, "graduation-$label-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        roots += root
        return root
    }

    /**
     * Wraps the real file-backed [DocumentFile] with a fixed, `MimeTypeMap`-unresolvable type —
     * see [FixedTypeDocumentFile]'s KDoc for why: without this, `RawDocumentFile.createFile`
     * appends whatever extension the MIME type resolves to onto the temp document's display
     * name, tripping the (correct, pre-existing) temp-name-integrity check for reasons that have
     * nothing to do with the copy/verify/rename/collision behavior under test here.
     */
    private fun writeSourceFile(dir: File, name: String, bytes: ByteArray): DocumentFile {
        val file = File(dir, name)
        file.writeBytes(bytes)
        return FixedTypeDocumentFile(DocumentFile.fromFile(file), "application/x-synthx-test-source")
    }

    private fun corruptByteAt(doc: DocumentFile, offset: Int) {
        val file = File(requireNotNull(doc.uri.path))
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset.toLong())
            val current = raf.readByte()
            raf.seek(offset.toLong())
            raf.writeByte(current + 1)
        }
    }

    private fun assertPublished(result: GraduationPublishResult): DocumentFile =
        (result as? GraduationPublishResult.Published)?.document
            ?: error("Expected a successful publish, got $result")

    private fun runCopyVerifyAndPublishBlocking(
        source: DocumentFile,
        destinationRoot: DocumentFile,
        displayName: String,
        expectedSongId: String,
    ): GraduationPublishResult = runBlocking {
        copyVerifyAndPublish(context, source, destinationRoot, displayName, expectedSongId)
    }
}
