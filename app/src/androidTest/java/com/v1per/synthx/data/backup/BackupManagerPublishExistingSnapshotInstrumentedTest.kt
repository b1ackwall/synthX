package com.v1per.synthx.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.FailOnSecondCreateDocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers R8-A13/P7-T6: [SafetySnapshotPromoter] used to call [SafBackupPublisher] directly,
 * bypassing [BackupManager]'s normal retention and outcome-recording, and generated a fresh
 * filename on every call with no memory of a prior successful publish — so a crash between a
 * successful publish and local staged-file cleanup caused a retry to republish the same content
 * under a new name. [BackupManager.publishExistingSnapshotTo] is the fix: the same identity ->
 * publish -> retention -> pipeline [BackupManager.createBackup] uses, made idempotent by content
 * (tier + reason + SHA-256) instead of by filename.
 *
 * Exercised directly against the `internal` directory-taking seam (same reason/technique as
 * [BackupRetentionInstrumentedTest] and [BackupListResultInstrumentedTest]: `DocumentFile
 * .fromTreeUri` needs a genuine SAF grant a plain instrumented test cannot obtain).
 */
@RunWith(AndroidJUnit4::class)
class BackupManagerPublishExistingSnapshotInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "publish-existing-${UUID.randomUUID()}"
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
    }

    private fun newRoot(): File {
        val root = File(context.cacheDir, "publish-existing-${UUID.randomUUID()}")
        root.mkdirs()
        roots += root
        return root
    }

    private fun stagedSnapshot(root: File, bytes: ByteArray = ByteArray(4096) { it.toByte() }): File {
        val staged = File(root.parentFile, "staged-${UUID.randomUUID()}.db")
        staged.writeBytes(bytes)
        roots += staged
        return staged
    }

    @Test
    fun retriedPromotionOfIdenticalBytesDoesNotRepublishUnderANewName() {
        val root = newRoot()
        val tierDirectory = DocumentFile.fromFile(root)
        val staged = stagedSnapshot(root)

        val first = backupManager.publishExistingSnapshotTo(
            tierDirectory,
            BackupTier.Auto,
            BackupReason.PRE_RESTORE_SAFETY,
            staged,
            dbVersion = 8,
        )
        // Simulates a crash between the first publish succeeding and the caller's own local
        // cleanup: the staged file (identical bytes) is still on disk, and a retry re-promotes it.
        val second = backupManager.publishExistingSnapshotTo(
            tierDirectory,
            BackupTier.Auto,
            BackupReason.PRE_RESTORE_SAFETY,
            staged,
            dbVersion = 8,
        )

        assertEquals("A retry must recognize its own prior publish, not generate a new name", first, second)
        val databaseFiles = root.listFiles().orEmpty().filter { BackupFileNames.isCompletedDatabaseName(it.name) }
        assertEquals("Exactly one database must exist after the retry", 1, databaseFiles.size)
    }

    @Test
    fun distinctPromotionsRunNormalAutoTierRetentionAndDoNotExceedTheLimit() {
        val root = newRoot()
        val tierDirectory = DocumentFile.fromFile(root)

        // BackupTier.Auto's limit is 30 — one more than that many DISTINCT (different-content)
        // promotions must not leave more than 30 complete pairs behind, per R8-A13's "a successful
        // restore can leave 31 complete Auto backups" claim.
        repeat(BackupTier.Auto.retention + 1) { index ->
            val staged = stagedSnapshot(root, bytes = ByteArray(64) { (it + index).toByte() })
            backupManager.publishExistingSnapshotTo(
                tierDirectory,
                BackupTier.Auto,
                BackupReason.PRE_RESTORE_SAFETY,
                staged,
                dbVersion = 8,
            )
        }

        val databaseFiles = root.listFiles().orEmpty().filter { BackupFileNames.isCompletedDatabaseName(it.name) }
        assertTrue(
            "Auto tier must not exceed its retention limit after repeated distinct promotions: " +
                "found ${databaseFiles.size}, limit ${BackupTier.Auto.retention}",
            databaseFiles.size <= BackupTier.Auto.retention,
        )
    }

    @Test
    fun aGenuinePublicationFailureReturnsFailureAndLeavesNoPartialArtifact() {
        val root = newRoot()
        // No metadata MIME support / always fails on the second create call (the metadata create)
        // — a genuine, non-crash publication failure, matching SafBackupPublisherInstrumentedTest's
        // own regression coverage for this fixture.
        val failingDirectory = FailOnSecondCreateDocumentFile(root)
        val staged = stagedSnapshot(root)

        try {
            backupManager.publishExistingSnapshotTo(
                failingDirectory,
                BackupTier.Auto,
                BackupReason.PRE_RESTORE_SAFETY,
                staged,
                dbVersion = 8,
            )
            org.junit.Assert.fail("A genuine publication failure must propagate, not return a name")
        } catch (_: BackupPublicationException) {
            // Expected — SafBackupPublisher's own cleanupAfterFailure already removes partials.
        }
        val databaseFiles = root.listFiles().orEmpty().filter { BackupFileNames.isCompletedDatabaseName(it.name) }
        assertFalse("A failed publication must leave no completed pair", databaseFiles.isNotEmpty())
    }
}
