package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.CorruptingRenameDocumentFile
import androidx.documentfile.provider.DecoyLookupDocumentFile
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.FailOnSecondCreateDocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafBackupPublisherInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { root -> root.deleteRecursively() }
    }

    @Test
    fun fileBackedPublicationRereadsExactBytesAndPublishesDatabaseLast() {
        val root = newRoot()
        val directory = DocumentFile.fromFile(root)
        val snapshotBytes = ByteArray(128 * 1024 + 17) { index -> (index * 31).toByte() }
        val snapshot = File(root.parentFile, "snapshot-${UUID.randomUUID()}.db")
        snapshot.writeBytes(snapshotBytes)
        roots += snapshot
        val digest = BackupHashing.hash(snapshot)
        val identity = BackupFileNames.create(randomId = "0123abcd")
        val metadata = BackupMetadata(
            timestamp = System.currentTimeMillis(),
            tier = BackupTier.Manual.name,
            dbVersion = 8,
            file = identity.databaseFileName,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )

        val published = SafBackupPublisher(context).publish(
            directory,
            identity,
            snapshot,
            digest,
            BackupMetadataCodec.encode(metadata),
        )

        assertEquals(identity.databaseFileName, published.database.name)
        assertEquals(identity.metadataFileName, published.metadata.name)
        assertArrayEquals(snapshotBytes, File(root, identity.databaseFileName).readBytes())
        assertFalse(File(root, identity.temporaryDatabaseFileName).exists())
        assertFalse(File(root, identity.temporaryMetadataFileName).exists())
        assertEquals(digest, BackupHashing.hash(File(root, identity.databaseFileName)))
    }

    @Test
    fun shortExternalCopyFailsExactVerification() {
        val root = newRoot()
        val external = File(root, "short.db.partial").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val expected = ByteDigest(sizeBytes = 4, sha256 = "00".repeat(32))

        try {
            SafBackupPublisher(context).verifyExternalBytes(Uri.fromFile(external), expected)
            fail("A short external copy must fail verification")
        } catch (error: BackupPublicationException) {
            assertTrue(error.message.orEmpty().contains("byte count mismatch"))
        }
    }

    @Test
    fun hashMismatchWithSameByteCountFailsVerification() {
        val root = newRoot()
        val external = File(root, "mismatch.db.partial").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val expected = ByteDigest(sizeBytes = 3, sha256 = "00".repeat(32))

        try {
            SafBackupPublisher(context).verifyExternalBytes(Uri.fromFile(external), expected)
            fail("A mismatched external hash must fail verification")
        } catch (error: BackupPublicationException) {
            assertTrue(error.message.orEmpty().contains("SHA-256 mismatch"))
        }
    }

    @Test
    fun metadataPublicationFailureCannotReturnSuccessOrLeaveDatabaseArtifact() {
        val root = newRoot()
        val directory = FailOnSecondCreateDocumentFile(root)
        val snapshot = File(root.parentFile, "snapshot-${UUID.randomUUID()}.db")
            .apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        roots += snapshot
        val digest = BackupHashing.hash(snapshot)
        val identity = BackupFileNames.create(randomId = "89abcdef")

        try {
            SafBackupPublisher(context).publish(
                directory,
                identity,
                snapshot,
                digest,
                "{}".encodeToByteArray(),
            )
            fail("Metadata publication failure must not return success")
        } catch (_: BackupPublicationException) {
            assertFalse(File(root, identity.databaseFileName).exists())
            assertFalse(File(root, identity.temporaryDatabaseFileName).exists())
            assertFalse(File(root, identity.temporaryMetadataFileName).exists())
            assertFalse(File(root, identity.metadataFileName).exists())
        }
    }

    @Test
    fun metadataCorruptedAfterRenameFailsPublicationDespiteRenameSucceeding() {
        val root = newRoot()
        val identity = BackupFileNames.create(randomId = "fedcba98")
        val directory = CorruptingRenameDocumentFile(root, identity.metadataFileName)
        val snapshot = File(root.parentFile, "snapshot-${UUID.randomUUID()}.db")
            .apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        roots += snapshot
        val digest = BackupHashing.hash(snapshot)
        val metadata = BackupMetadata(
            timestamp = System.currentTimeMillis(),
            tier = BackupTier.Manual.name,
            dbVersion = 8,
            file = identity.databaseFileName,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )

        try {
            SafBackupPublisher(context).publish(
                directory,
                identity,
                snapshot,
                digest,
                BackupMetadataCodec.encode(metadata),
            )
            fail("Metadata silently altered after rename must not be published as a success")
        } catch (error: BackupPublicationException) {
            assertTrue(error.message.orEmpty().contains("metadata SHA-256 mismatch"))
        }
        // The database's recognizable name must never be published when metadata failed its
        // post-rename reread — publication is all-or-nothing.
        assertFalse(File(root, identity.databaseFileName).exists())
    }

    @Test
    fun metadataIsVerifiedThroughTheRenamedDocumentNotAFreshNameLookup() {
        assertPublicationIgnoresDecoyLookupFor { identity -> identity.metadataFileName }
    }

    @Test
    fun databaseIsVerifiedThroughTheRenamedDocumentNotAFreshNameLookup() {
        assertPublicationIgnoresDecoyLookupFor { identity -> identity.databaseFileName }
    }

    /**
     * After a successful rename, [DecoyLookupDocumentFile] answers `findFile(finalName)` with
     * a different document holding different bytes. Publication must still succeed, verify and
     * return the renamed documents, and leave the decoy untouched — i.e. never re-resolve the
     * published document by name. `findFile`-based code would verify the decoy's bytes instead
     * and fail with a SHA-256/byte-count mismatch (then delete the decoy in cleanup).
     */
    private fun assertPublicationIgnoresDecoyLookupFor(decoyNameOf: (BackupFileIdentity) -> String) {
        val root = newRoot()
        val identity = BackupFileNames.create(randomId = UUID.randomUUID().toString().take(8))
        val decoyBytes = "decoy — not the published document".encodeToByteArray()
        val decoy = File(root.parentFile, "decoy-${UUID.randomUUID()}")
            .apply { writeBytes(decoyBytes) }
        roots += decoy
        val directory = DecoyLookupDocumentFile(root, decoyNameOf(identity), decoy)
        val snapshotBytes = ByteArray(8192) { index -> (index * 13).toByte() }
        val snapshot = File(root.parentFile, "snapshot-${UUID.randomUUID()}.db")
            .apply { writeBytes(snapshotBytes) }
        roots += snapshot
        val digest = BackupHashing.hash(snapshot)
        val metadataBytes = BackupMetadataCodec.encode(
            BackupMetadata(
                timestamp = System.currentTimeMillis(),
                tier = BackupTier.Manual.name,
                dbVersion = 8,
                file = identity.databaseFileName,
                sizeBytes = digest.sizeBytes,
                sha256 = digest.sha256,
            )
        )

        val published = SafBackupPublisher(context).publish(
            directory,
            identity,
            snapshot,
            digest,
            metadataBytes,
        )

        assertEquals(Uri.fromFile(File(root, identity.databaseFileName)), published.database.uri)
        assertEquals(Uri.fromFile(File(root, identity.metadataFileName)), published.metadata.uri)
        assertArrayEquals(snapshotBytes, File(root, identity.databaseFileName).readBytes())
        assertArrayEquals(metadataBytes, File(root, identity.metadataFileName).readBytes())
        assertArrayEquals(decoyBytes, decoy.readBytes())
    }

    private fun newRoot(): File {
        val root = File(context.cacheDir, "saf-publisher-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        roots += root
        return root
    }
}
