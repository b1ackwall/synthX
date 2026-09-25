package com.v1per.synthx.data.backup

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises PlaylistExportManager's subfolder placement and one-time legacy migration against a
 * real file-backed [DocumentFile] root, the same approach SafBackupPublisherInstrumentedTest uses.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistExportFolderInstrumentedTest {
    private lateinit var database: SynthXDatabase
    private lateinit var manager: PlaylistExportManager
    private lateinit var rootFile: File

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        manager = PlaylistExportManager(
            context = context,
            database = database,
            playlistDao = database.playlistDao(),
            songDao = database.songDao(),
            artistDao = database.artistDao(),
            albumDao = database.albumDao(),
        )
        rootFile = File(context.cacheDir, "export-folder-${UUID.randomUUID()}").also { it.mkdirs() }
        PlaylistExportManager.resetMigrationGuardForTest()
    }

    @After fun tearDown() {
        database.close()
        rootFile.deleteRecursively()
        PlaylistExportManager.resetMigrationGuardForTest()
    }

    private fun exportDir() = File(rootFile, PlaylistExportManager.EXPORT_FOLDER_NAME)
    private fun rootExportFiles() = rootFile.listFiles().orEmpty().filter { it.isFile }

    @Test fun freshRootWritesOnlyIntoSubfolder() {
        manager.publishExport(DocumentFile.fromFile(rootFile), "one".toByteArray())

        assertTrue(rootExportFiles().isEmpty())
        assertEquals("one", File(exportDir(), "playlists_export.json").readText())
    }

    @Test fun preexistingRootFilesAreRelocatedWithGenerationsIntact() {
        // Current + generations 1..3 + a crashed .tmp, all at root as the old code left them.
        File(rootFile, "playlists_export.json").writeText("current")
        File(rootFile, "playlists_export.1.json").writeText("gen1")
        File(rootFile, "playlists_export.2.json").writeText("gen2")
        File(rootFile, "playlists_export.3.json").writeText("gen3")
        File(rootFile, "playlists_export.json.tmp").writeText("partial")
        File(rootFile, "playlist_seed_manifest.json").writeText("seed")
        File(rootFile, "Daily").mkdirs()

        manager.publishExport(DocumentFile.fromFile(rootFile), "new".toByteArray())

        // Nothing export-related left at root; seed manifest and tier folder untouched.
        assertEquals(listOf("playlist_seed_manifest.json"), rootExportFiles().map { it.name })
        assertEquals("seed", File(rootFile, "playlist_seed_manifest.json").readText())
        assertTrue(File(rootFile, "Daily").isDirectory)

        // Migrated history shifted up exactly one generation by the new write: nothing lost,
        // nothing duplicated.
        val dir = exportDir()
        assertEquals("new", File(dir, "playlists_export.json").readText())
        assertEquals("current", File(dir, "playlists_export.1.json").readText())
        assertEquals("gen1", File(dir, "playlists_export.2.json").readText())
        assertEquals("gen2", File(dir, "playlists_export.3.json").readText())
        assertEquals("gen3", File(dir, "playlists_export.4.json").readText())
        assertFalse(File(dir, "playlists_export.5.json").exists())
        val leftovers = dir.listFiles().orEmpty().map { it.name }
            .filter { it.contains("migrating") || it.endsWith(".tmp") || it.contains(".tmp.") }
        assertTrue("staging/temp files left behind: $leftovers", leftovers.isEmpty())
    }

    @Test fun migrationNeverOverwritesExistingSubfolderFile() {
        exportDir().mkdirs()
        File(exportDir(), "playlists_export.json").writeText("already-here")
        File(rootFile, "playlists_export.json").writeText("legacy-different")

        val root = DocumentFile.fromFile(rootFile)
        manager.migrateLegacyRootFiles(root, root.findFile(PlaylistExportManager.EXPORT_FOLDER_NAME)!!)

        assertEquals("already-here", File(exportDir(), "playlists_export.json").readText())
        // Differing content is kept at root rather than deleted.
        assertEquals("legacy-different", File(rootFile, "playlists_export.json").readText())
    }

    @Test fun migrationRunsOncePerProcessAndBackupTierListingIgnoresTheFolder() {
        val root = DocumentFile.fromFile(rootFile)
        manager.publishExport(root, "a".toByteArray())
        // A file appearing at root after the first write is not swept up again this process.
        File(rootFile, "playlists_export.json").writeText("late")
        manager.publishExport(root, "b".toByteArray())
        assertEquals("late", File(rootFile, "playlists_export.json").readText())

        // Tier scan looks tiers up by exact name; the sibling folder must not be treated as one.
        BackupTier.entries.forEach { File(rootFile, it.folderName).mkdirs() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = BackupManager.create(context).classifyBackupList(root)
        assertEquals(BackupListResult.Empty, result)
    }
}
