package com.v1per.synthx.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pre-migration backup copies an older live database with a raw SQLite handle while the
 * startup gate is still closed, so opening Room can never migrate the file before it is saved.
 * Uses the [BackupManager.resolveTierDir] seam: a test cannot hold a persisted SAF tree grant.
 */
@RunWith(AndroidJUnit4::class)
class PreMigrationBackupInstrumentedTest : PhaseBRestoreTestBase() {
    private val testId = "pre-migration-${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            context.getSharedPreferences("$testId-$name", mode)
    }
    private val tierRoot = File(context.cacheDir, testId).apply { mkdirs() }

    @After fun cleanUp() {
        tierRoot.deleteRecursively()
        context.deleteSharedPreferences("$testId-synthx_prefs")
        DatabaseStartupGate.markReadyForTest()
    }

    @Test fun preMigrationBackupSavesTheOlderFileWithoutOpeningRoom() = runBlocking {
        val olderVersion = SynthXDatabase.VERSION - 1
        createLiveAtVersion(olderVersion)
        PreferencesManager(isolated).backupFolderUri = "content://slice2.test/tree/backups"
        DatabaseStartupGate.installForTest(closedGate(olderVersion))

        val result = FileTierBackupManager(isolated, tierRoot).createPreMigrationBackup()

        assertTrue("backup failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertFalse("Room must stay closed", DatabaseStartupGate.isReady())
        assertEquals(LiveDatabaseVersion.Present(olderVersion), LiveDatabaseVersionReader.read(liveFile()))
        val published = tierRoot.walkTopDown().single { it.isFile && it.name == result.getOrThrow() }
        assertEquals(olderVersion, userVersion(published))
    }

    private fun createLiveAtVersion(version: Int) {
        val builderName = "pre-migration-${UUID.randomUUID()}.db"
        registeredDatabaseNames += builderName
        migrationHelper.createDatabase(builderName, version).close()
        deleteFileSet(liveFile())
        context.getDatabasePath(builderName).copyTo(liveFile(), overwrite = true)
        context.deleteDatabase(builderName)
    }

    /** A gate that never became ready and fails the test if anything tries to migrate. */
    private fun closedGate(liveVersion: Int) = DatabaseStartupController(
        currentVersion = SynthXDatabase.VERSION,
        awaitRestoreNormal = {},
        inspectLiveVersion = { LiveDatabaseVersion.Present(liveVersion) },
        performMigration = { error("the pre-migration backup must not migrate") },
    )

    private fun userVersion(file: File): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }

    /** Resolves every backup tier to a plain folder instead of a SAF tree. */
    private class FileTierBackupManager(context: Context, private val root: File) : BackupManager(context) {
        override fun resolveTierDir(treeUri: Uri, tier: BackupTier, create: Boolean): DocumentFile? =
            DocumentFile.fromFile(File(root, tier.folderName).apply { mkdirs() })
    }
}
