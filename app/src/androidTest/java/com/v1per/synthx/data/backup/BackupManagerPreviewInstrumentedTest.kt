package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers R7-F15/P7-T7: [BackupManager.previewBackup] used to stage a candidate's full bytes
 * with no free-space preflight at all — [BackupManager.restore] already checked via
 * [RestoreTransaction.ensureSpace] before ITS own staging copy, but preview had nothing. This
 * mirrors [BackupManagerVerificationInstrumentedTest]'s harness (a real, file-backed
 * [BackupInfo] rather than a genuine SAF tree grant).
 */
@RunWith(AndroidJUnit4::class)
class BackupManagerPreviewInstrumentedTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val preferenceName = "backup-preview-${UUID.randomUUID()}"
    private val context = object : android.content.ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            appContext.getSharedPreferences("$preferenceName-$name", mode)
    }
    private val backupManager = BackupManager(context)
    private val roots = mutableListOf<File>()
    private val createdDatabaseNames = mutableSetOf<String>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        createdDatabaseNames.forEach(context::deleteDatabase)
        appContext.deleteSharedPreferences("$preferenceName-synthx_prefs")
    }

    private fun newRoot(): File {
        val root = File(context.cacheDir, "backup-preview-${UUID.randomUUID()}")
        root.mkdirs()
        roots += root
        return root
    }

    /** A real, valid current-schema standalone snapshot — small, genuinely readable/countable. */
    private fun realBackupFile(root: File): File {
        val builderName = "preview-${UUID.randomUUID()}.db"
        createdDatabaseNames += builderName
        val database = SynthXDatabase.createStandalone(context, builderName)
        val destination = File(root, "preview-source-${UUID.randomUUID()}.db")
        try {
            database.openHelper.writableDatabase
            BackupSnapshotter.createFromRoom(database.openHelper.writableDatabase, destination)
        } finally {
            database.close()
            context.deleteDatabase(builderName)
        }
        return destination
    }

    private fun backupInfoFor(databaseFile: File, sizeBytes: Long) = BackupInfo(
        databaseUri = Uri.fromFile(databaseFile),
        metadataUri = null,
        fileName = databaseFile.name,
        tier = BackupTier.Manual,
        timestamp = System.currentTimeMillis(),
        reason = null,
        sizeBytes = sizeBytes,
        restoreEligible = true,
    )

    @Test
    fun previewSucceedsForARealBackupWithItsActualDeclaredSize() = runBlocking {
        val root = newRoot()
        val file = realBackupFile(root)
        val info = backupInfoFor(file, sizeBytes = file.length())

        val result = backupManager.previewBackup(info)

        assertTrue("Preview of a genuinely small backup must pass free space: ${result.exceptionOrNull()}", result.isSuccess)
    }

    /**
     * The core R7-F15 fix: previewBackup must reject BEFORE it starts copying when the
     * declared size can't possibly fit — proven here with an impossibly large declared size
     * against a real, valid, tiny backup file (no StatFs faking needed: no real device has
     * this much free space). This is also this task's designated mutation test target — see
     * BackupManager.previewBackup's `ensureSpace` call.
     */
    @Test
    fun previewFailsFastWhenDeclaredSizeCannotPossiblyFit() = runBlocking {
        val root = newRoot()
        val file = realBackupFile(root)
        val info = backupInfoFor(file, sizeBytes = Long.MAX_VALUE / 4)

        val result = backupManager.previewBackup(info)

        assertTrue("Expected the free-space preflight to fail before any copy work", result.isFailure)
        assertTrue(
            "Expected a free-space message, got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("storage", ignoreCase = true),
        )
    }
}
