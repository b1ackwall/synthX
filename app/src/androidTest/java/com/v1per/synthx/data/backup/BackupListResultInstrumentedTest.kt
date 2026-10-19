package com.v1per.synthx.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [BackupManager.listBackups] must distinguish four situations — no folder configured, an
 * unreachable folder (missing tier directories or a revoked/bogus SAF grant), a genuinely empty
 * folder, and an actual list — plus the per-entry ineligibility reasons, exercised through real
 * files rather than the pure fixtures in [BackupIneligibilityReasonTest].
 *
 * `classifyBackupList` is tested directly against a `DocumentFile.fromFile`-backed root, the same
 * technique [SafBackupPublisherInstrumentedTest] uses to avoid needing a genuine SAF tree grant
 * (which `DocumentFile.fromTreeUri` requires and a plain instrumented test cannot obtain).
 */
@RunWith(AndroidJUnit4::class)
class BackupListResultInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val preferenceName = "backup-list-result-${UUID.randomUUID()}"

    // Both PreferencesManager and BackupManager must share this same isolated context — using
    // the raw appContext for one of them would silently read/write the app's real
    // "synthx_prefs" file instead of this test's isolated one.
    private val context = object : android.content.ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            appContext.getSharedPreferences("$preferenceName-$name", mode)
    }
    private val preferences = PreferencesManager(context)
    private val backupManager = BackupManager(context)
    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        appContext.deleteSharedPreferences("$preferenceName-synthx_prefs")
    }

    private fun newRoot(): File {
        val root = File(context.cacheDir, "backup-list-test-${UUID.randomUUID()}")
        root.mkdirs()
        roots += root
        return root
    }

    // ---------------------------------------------------------- listBackups() public entry point

    @Test
    fun noFolderConfiguredWhenNothingIsPersisted() = runBlocking {
        preferences.backupFolderUri = null
        assertEquals(BackupListResult.NoFolderConfigured, backupManager.listBackups())
    }

    @Test
    fun folderUnreachableWhenTheGrantDoesNotResolveToAnyRealProvider() = runBlocking {
        // No real content provider backs this authority — simulates a revoked SAF grant.
        preferences.backupFolderUri =
            "content://synthx.test.unreachable/tree/primary%3ABogus".toUri().toString()
        assertEquals(BackupListResult.FolderUnreachable, backupManager.listBackups())
    }

    // ---------------------------------------------------------- classifyBackupList(root): folder state

    @Test
    fun folderUnreachableWhenTierDirectoriesDoNotExist() {
        val root = DocumentFile.fromFile(newRoot())
        // Deliberately no Daily/Auto/Manual subdirectories under the root.
        assertEquals(BackupListResult.FolderUnreachable, backupManager.classifyBackupList(root))
    }

    @Test
    fun emptyWhenTierDirectoriesExistButHoldNothing() {
        val rootFile = newRoot()
        BackupTier.entries.forEach { File(rootFile, it.folderName).mkdirs() }
        val root = DocumentFile.fromFile(rootFile)
        assertEquals(BackupListResult.Empty, backupManager.classifyBackupList(root))
    }

    // ---------------------------------------------------------- classifyBackupList(root): per-entry reasons

    @Test
    fun successListDistinguishesEligibleFromEachIneligibilityCause() {
        val rootFile = newRoot()
        val tierDir = File(rootFile, BackupTier.Manual.folderName).apply { mkdirs() }

        val eligible = writeBackupPair(tierDir, randomId = "00000001", dbVersion = 8, sizeMatches = true)
        val missingSidecar = writeDatabaseOnly(tierDir, randomId = "00000002")
        val sizeMismatch = writeBackupPair(tierDir, randomId = "00000003", dbVersion = 8, sizeMatches = false)
        val unsupportedSchema = writeBackupPair(tierDir, randomId = "00000004", dbVersion = 9999, sizeMatches = true)

        val result = backupManager.classifyBackupList(DocumentFile.fromFile(rootFile))
        assertTrue(result is BackupListResult.Success)
        val byName = (result as BackupListResult.Success).backups.associateBy { it.fileName }
        assertEquals(4, byName.size)

        val eligibleInfo = byName.getValue(eligible)
        assertTrue(eligibleInfo.restoreEligible)
        assertNull(eligibleInfo.restoreIneligibleReason)

        assertEquals(
            BackupIneligibilityReason.MissingMetadata,
            byName.getValue(missingSidecar).restoreIneligibleReason,
        )
        assertEquals(
            BackupIneligibilityReason.SizeMismatch(expectedBytes = 999L, actualBytes = 128L),
            byName.getValue(sizeMismatch).restoreIneligibleReason,
        )
        assertEquals(
            BackupIneligibilityReason.UnsupportedSchema(dbVersion = 9999),
            byName.getValue(unsupportedSchema).restoreIneligibleReason,
        )
    }

    @Test
    fun allIneligibleListIsStillSuccessNotEmptyOrUnreachable() {
        val rootFile = newRoot()
        val tierDir = File(rootFile, BackupTier.Manual.folderName).apply { mkdirs() }
        writeDatabaseOnly(tierDir, randomId = "00000005")

        val result = backupManager.classifyBackupList(DocumentFile.fromFile(rootFile))
        assertTrue(result is BackupListResult.Success)
        val backups = (result as BackupListResult.Success).backups
        assertEquals(1, backups.size)
        assertTrue(backups.none { it.restoreEligible })
    }

    /** Writes a `.db` (128 bytes) + matching `.json` sidecar; `sizeMatches=false` lies about size. */
    private fun writeBackupPair(tierDir: File, randomId: String, dbVersion: Int, sizeMatches: Boolean): String {
        val identity = BackupFileNames.create(randomId = randomId)
        val databaseFile = File(tierDir, identity.databaseFileName)
        databaseFile.writeBytes(ByteArray(128) { 1 })
        val actualSize = if (sizeMatches) 128L else 999L
        val metadata = BackupMetadata(
            timestamp = 1_000L,
            tier = BackupTier.Manual.name,
            dbVersion = dbVersion,
            file = identity.databaseFileName,
            sizeBytes = actualSize,
            sha256 = "ab".repeat(32),
        )
        File(tierDir, identity.metadataFileName).writeBytes(BackupMetadataCodec.encode(metadata))
        return identity.databaseFileName
    }

    private fun writeDatabaseOnly(tierDir: File, randomId: String): String {
        val identity = BackupFileNames.create(randomId = randomId)
        File(tierDir, identity.databaseFileName).writeBytes(ByteArray(128) { 1 })
        return identity.databaseFileName
    }
}
