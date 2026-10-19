package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [BackupManager.verifyMostRecentBackup] must check only a [BackupInfo.restoreEligible] backup,
 * record an empty/unusable listing as skipped rather than as a successful verification, and
 * validate with `migrationSnapshot = true` so supported older-schema backups pass. Exercised through [BackupManager.verifyBackupList] — split
 * out from `verifyMostRecentBackup` the same way [BackupManager.classifyBackupList] is split out
 * from `listBackups`, so a [BackupListResult] can be supplied directly without a genuine SAF tree
 * grant.
 */
@RunWith(AndroidJUnit4::class)
class BackupManagerVerificationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val preferenceName = "backup-verify-${UUID.randomUUID()}"
    private val context = object : android.content.ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            appContext.getSharedPreferences("$preferenceName-$name", mode)
    }
    private val backupManager = BackupManager(context)
    private val roots = mutableListOf<File>()
    private val createdDatabaseNames = mutableSetOf<String>()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SynthXDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        createdDatabaseNames.forEach(context::deleteDatabase)
        appContext.deleteSharedPreferences("$preferenceName-synthx_prefs")
        BackupOutcomeStore.resetForTest()
    }

    private fun newRoot(): File {
        val root = File(context.cacheDir, "backup-verify-${UUID.randomUUID()}")
        root.mkdirs()
        roots += root
        return root
    }

    private fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    /**
     * A real, valid current-schema standalone snapshot — passes structural validation as-is.
     * Named via [BackupFileNames.create] because [BackupMetadata.requireCompleteForPublication]
     * (which [BackupMetadataCodec.encode] enforces) rejects any filename that isn't a real
     * completed-backup name.
     */
    private fun currentSchemaBackup(root: File): File {
        val builderName = "verify-current-${UUID.randomUUID()}.db"
        createdDatabaseNames += builderName
        val database = SynthXDatabase.createStandalone(context, builderName)
        val destination = File(root, BackupFileNames.create(randomId = randomId()).databaseFileName)
        try {
            database.openHelper.writableDatabase
            BackupSnapshotter.createFromRoom(database.openHelper.writableDatabase, destination)
        } finally {
            database.close()
            context.deleteDatabase(builderName)
        }
        return destination
    }

    /** A real, valid v8-schema standalone snapshot — the ground truth for TRUSTED_OLDER_SUPPORTED. */
    private fun olderSupportedSchemaBackup(root: File): File {
        val name = "verify-v8-${UUID.randomUUID()}.db"
        createdDatabaseNames += name
        val database = migrationHelper.createDatabase(name, 8)
        val destination = File(root, BackupFileNames.create(randomId = randomId()).databaseFileName)
        try {
            BackupSnapshotter.createFromRoom(database, destination)
        } finally {
            database.close()
        }
        return destination
    }

    private fun backupInfoFor(
        databaseFile: File,
        dbVersion: Int,
        restoreEligible: Boolean,
        tier: BackupTier = BackupTier.Manual,
    ): BackupInfo {
        val digest = BackupHashing.hash(databaseFile)
        val metadata = BackupMetadata(
            timestamp = System.currentTimeMillis(),
            tier = tier.name,
            dbVersion = dbVersion,
            file = databaseFile.name,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )
        val metadataFile = File(databaseFile.parentFile, databaseFile.name.removeSuffix(".db") + ".json")
        metadataFile.writeBytes(BackupMetadataCodec.encode(metadata))
        return BackupInfo(
            databaseUri = Uri.fromFile(databaseFile),
            metadataUri = Uri.fromFile(metadataFile),
            fileName = databaseFile.name,
            tier = tier,
            timestamp = metadata.timestamp,
            reason = null,
            sizeBytes = digest.sizeBytes,
            restoreEligible = restoreEligible,
        )
    }

    // ---------------------------------------------------------------- eligibility filtering

    @Test
    fun verifyBackupListSkipsAnIneligibleFirstListedEntryAndVerifiesTheEligibleOne() = runBlocking {
        val root = newRoot()
        // Ineligible entry deliberately points at a file that would fail verification if it were
        // ever opened — proving the eligible entry, not this one, is what actually gets checked.
        val corruptFile = File(root, "corrupt.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ineligible = BackupInfo(
            databaseUri = Uri.fromFile(corruptFile),
            metadataUri = null,
            fileName = corruptFile.name,
            tier = BackupTier.Manual,
            timestamp = 2_000L,
            reason = null,
            sizeBytes = 3L,
            restoreEligible = false,
        )
        val eligible = backupInfoFor(currentSchemaBackup(root), SynthXDatabase.VERSION, restoreEligible = true)
            .let { it.copy(timestamp = 1_000L) }

        val result = backupManager.verifyBackupList(BackupListResult.Success(listOf(ineligible, eligible)))

        assertTrue("Verification must succeed by checking the eligible entry", result.isSuccess)
        val outcome = BackupOutcomeStore.lastVerificationOutcome.value
        assertEquals(true, outcome?.succeeded)
        assertEquals(false, outcome?.skippedNoEligibleBackup)
    }

    // ---------------------------------------------------------------- no eligible backup

    @Test
    fun noEligibleBackupRecordsDistinctSkippedOutcomeNotAFalseSuccess() = runBlocking {
        val root = newRoot()
        val corruptFile = File(root, "corrupt.db").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val onlyIneligible = BackupInfo(
            databaseUri = Uri.fromFile(corruptFile),
            metadataUri = null,
            fileName = corruptFile.name,
            tier = BackupTier.Manual,
            timestamp = 1_000L,
            reason = null,
            sizeBytes = 3L,
            restoreEligible = false,
        )

        val result = backupManager.verifyBackupList(BackupListResult.Success(listOf(onlyIneligible)))

        assertTrue(result.isSuccess)
        val outcome = BackupOutcomeStore.lastVerificationOutcome.value
        assertEquals(false, outcome?.succeeded)
        assertEquals(true, outcome?.skippedNoEligibleBackup)
    }

    @Test
    fun emptyListingRecordsSkippedOutcomeNotSuccess() = runBlocking {
        val result = backupManager.verifyBackupList(BackupListResult.Empty)

        assertTrue(result.isSuccess)
        val outcome = BackupOutcomeStore.lastVerificationOutcome.value
        assertEquals(false, outcome?.succeeded)
        assertEquals(true, outcome?.skippedNoEligibleBackup)
    }

    // ---------------------------------------------------------------- migrationSnapshot regression

    @Test
    fun genuinelySupportedOlderSchemaBackupPassesVerification() = runBlocking {
        val root = newRoot()
        val olderEligible = backupInfoFor(olderSupportedSchemaBackup(root), dbVersion = 8, restoreEligible = true)

        val result = backupManager.verifyBackupList(BackupListResult.Success(listOf(olderEligible)))

        assertTrue(
            "A TRUSTED_OLDER_SUPPORTED backup must pass verification " +
                "(migrationSnapshot=true, not the old hardcoded false): ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        val outcome = BackupOutcomeStore.lastVerificationOutcome.value
        assertEquals(true, outcome?.succeeded)
    }
}
