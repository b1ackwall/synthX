package com.v1per.synthx.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.SelectiveDeleteRefusalDocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers R8-A12/P7-T6: [BackupManager.enforceRetention]'s deletion callback used to discard both
 * the SAF [DocumentFile.delete] Boolean return and any thrown exception, so a victim that failed
 * to delete (partially or completely) vanished from accounting with no trace. Exercised directly
 * against [BackupManager.enforceRetention] (made `internal` for exactly this) with a real,
 * file-backed [DocumentFile] tier directory instead of a genuine SAF tree grant — same technique
 * [BackupListResultInstrumentedTest] uses for [BackupManager.classifyBackupList].
 */
@RunWith(AndroidJUnit4::class)
class BackupRetentionInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "backup-retention-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    private val backupManager = BackupManager(context)
    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        appContext.deleteSharedPreferences("$testId-synthx_prefs")
        BackupOutcomeStore.resetForTest()
    }

    private fun newRoot(): File {
        val root = File(context.cacheDir, "retention-${UUID.randomUUID()}")
        root.mkdirs()
        roots += root
        return root
    }

    private fun writePair(root: File, id: String, timestamp: Long, tier: BackupTier = BackupTier.Manual): String {
        val identity = BackupFileNames.create(randomId = id)
        val databaseFile = File(root, identity.databaseFileName)
        databaseFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        val digest = BackupHashing.hash(databaseFile)
        val metadata = BackupMetadata(
            timestamp = timestamp,
            tier = tier.name,
            dbVersion = 8,
            file = identity.databaseFileName,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )
        File(root, identity.metadataFileName).writeBytes(BackupMetadataCodec.encode(metadata))
        return identity.databaseFileName
    }

    /** Fills a tier past its retention limit with one designated oldest victim plus filler pairs. */
    private fun fillPastLimit(root: File, tier: BackupTier): Pair<String, String> {
        val victim = writePair(root, "00000001", timestamp = 1_000, tier = tier)
        // Filler pairs, newer than the victim, up to and one past the tier's retention limit —
        // the last one written is the "current" publication under test.
        var current = victim
        for (index in 2..(tier.retention + 1)) {
            current = writePair(root, index.toString().padStart(8, '0'), timestamp = 1_000L * index, tier = tier)
        }
        return victim to current
    }

    @Test
    fun cleanPruneDeletesPastLimitAndRecordsCleanRetentionOutcome() {
        val root = newRoot()
        val (victim, current) = fillPastLimit(root, BackupTier.Manual)
        val tierDirectory = DocumentFile.fromFile(root)

        backupManager.enforceRetention(
            tierDirectory,
            BackupTier.Manual,
            BackupFileIdentity(current.removeSuffix(".db")),
        )

        assertFalse("Past-limit victim database must be deleted", File(root, victim).exists())
        assertTrue("Current publication must survive", File(root, current).exists())
        val outcome = BackupOutcomeStore.lastRetentionOutcome.value
        assertEquals(1, outcome?.attempted)
        assertEquals(1, outcome?.succeeded)
        assertFalse(outcome?.hadFailures ?: true)
    }

    @Test
    fun refusedDeleteIsReportedAsRetentionFailureWithoutFlippingPublishOutcome() {
        val root = newRoot()
        val (victimName, current) = fillPastLimit(root, BackupTier.Manual)
        val victimMetadataName = victimName.removeSuffix(".db") + ".json"
        val refusingDirectory = SelectiveDeleteRefusalDocumentFile(
            root,
            refuseNames = setOf(victimName, victimMetadataName),
        )

        // Simulate a just-succeeded publish, exactly as BackupManager.createBackup's own
        // `.also { recordBackupOutcome(...) }` would — before retention runs against it.
        BackupOutcomeStore.recordBackupOutcome(context, BackupTier.Manual, null, Result.success(current))

        backupManager.enforceRetention(
            refusingDirectory,
            BackupTier.Manual,
            BackupFileIdentity(current.removeSuffix(".db")),
        )

        val retention = BackupOutcomeStore.lastRetentionOutcome.value
        assertEquals(1, retention?.attempted)
        assertEquals(0, retention?.succeeded)
        assertEquals(listOf(victimName), retention?.failedDatabaseNames)
        assertTrue(retention?.hadFailures ?: false)

        // The triggering publish's own outcome must stay untouched by retention's failure.
        val publish = BackupOutcomeStore.lastBackupOutcome.value
        assertTrue("Publish outcome must remain successful", publish?.succeeded ?: false)
    }

    @Test
    fun preExistingOrphanHalfIsCleanedUpOnTheNextRetentionPass() {
        val root = newRoot()
        val current = writePair(root, "00000002", timestamp = 2_000)
        // A pre-existing orphan: database half with no matching metadata, as R8-A12 describes a
        // partial pruning deletion or a crash mid-publish would leave behind.
        val orphanIdentity = BackupFileNames.create(randomId = "00000001")
        File(root, orphanIdentity.databaseFileName).writeBytes(byteArrayOf(9, 9, 9))
        val tierDirectory = DocumentFile.fromFile(root)

        backupManager.enforceRetention(
            tierDirectory,
            BackupTier.Manual,
            BackupFileIdentity(current.removeSuffix(".db")),
        )

        assertFalse(
            "Orphan database half with no metadata must be swept",
            File(root, orphanIdentity.databaseFileName).exists(),
        )
        assertTrue("Current publication must survive the sweep", File(root, current).exists())
    }

    @Test
    fun preExistingOrphanMetadataHalfIsCleanedUpOnTheNextRetentionPass() {
        val root = newRoot()
        val current = writePair(root, "00000002", timestamp = 2_000)
        val orphanIdentity = BackupFileNames.create(randomId = "00000001")
        val metadata = BackupMetadata(
            timestamp = 1_000,
            tier = BackupTier.Manual.name,
            dbVersion = 8,
            file = orphanIdentity.databaseFileName,
            sizeBytes = 4L,
            sha256 = "ab".repeat(32),
        )
        File(root, orphanIdentity.metadataFileName).writeBytes(BackupMetadataCodec.encode(metadata))
        val tierDirectory = DocumentFile.fromFile(root)

        backupManager.enforceRetention(
            tierDirectory,
            BackupTier.Manual,
            BackupFileIdentity(current.removeSuffix(".db")),
        )

        assertFalse(
            "Orphan metadata half with no database must be swept",
            File(root, orphanIdentity.metadataFileName).exists(),
        )
        assertTrue(File(root, current).exists())
    }

    @Test
    fun nothingToPruneDoesNotRecordAFailure() {
        val root = newRoot()
        val current = writePair(root, "00000001", timestamp = 1_000)
        val tierDirectory = DocumentFile.fromFile(root)

        backupManager.enforceRetention(
            tierDirectory,
            BackupTier.Manual,
            BackupFileIdentity(current.removeSuffix(".db")),
        )

        val outcome = BackupOutcomeStore.lastRetentionOutcome.value
        assertEquals(0, outcome?.attempted)
        assertFalse(outcome?.hadFailures ?: true)
        assertNull(outcome?.failedDatabaseNames?.firstOrNull())
    }
}
