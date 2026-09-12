package com.v1per.synthx.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the public repository methods and real backup drain, not a copy of their ordering. */
@RunWith(AndroidJUnit4::class)
class RepositoryDeletionInstrumentedTest {
    private lateinit var context: DeletionTestContext
    private lateinit var database: SynthXDatabase
    private lateinit var repository: SongRepositoryImpl

    @Before fun setUp() = runBlocking {
        RestoreStartup.await()
        RestoreCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
        DestructiveActionTracker.resetForTest()
        context = DeletionTestContext(ApplicationProvider.getApplicationContext())
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        val exports = PlaylistExportManager(
            context, database.playlistDao(), database.songDao(), database.artistDao(), database.albumDao(),
        )
        repository = SongRepositoryImpl(
            context, database, database.songDao(), database.artistDao(), database.albumDao(),
            database.playlistDao(), database.queueDao(),
            FolderScanner(
                context, database, database.songDao(), database.artistDao(), database.albumDao(),
                database.songArtistDao(), exports,
            ),
            BackupManager(context), exports,
        )
        database.songDao().upsertSong(
            SongEntity(
                id = "song", title = "Song", albumId = null, duration = 1,
                filePath = "test://song", albumArtUri = null, year = null, dateAdded = 1,
            ),
        )
        database.playlistDao().insertPlaylist(
            PlaylistEntity("playlist", "Playlist", null, null, 0, 1, 1, null, 120, 42L),
        )
        database.playlistDao().insertPlaylistSong(PlaylistSongEntity("playlist", "song", 0, 1))
        File(context.filesDir, "art").mkdirs()
        File(context.filesDir, "art/art_song.jpg").writeText("test art")
    }

    @After fun tearDown() {
        context.afterBackup = null
        database.close()
        context.cleanUp()
        RestoreCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
        DestructiveActionTracker.resetForTest()
    }

    @Test fun playlistDeletionCompletesThroughRealBackupDrain() = runBlocking {
        withTimeout(3_000) { repository.deletePlaylist("playlist") }
        assertEquals(0, database.playlistDao().getPlaylistCount())
        assertTrue(database.playlistDao().getSongIdsForPlaylist("playlist").isEmpty())
        assertNotNull(database.songDao().getSongById("song"))
        assertTrue(File(context.filesDir, "art/art_song.jpg").exists())
        assertBackupAttempt(BackupReason.DELETE_PLAYLIST)
        assertEquals(1, PreferencesManager(context).destructiveActionsSinceBackup)
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun songDeletionCompletesThroughRealBackupDrain() = runBlocking {
        val outcome = withTimeout(3_000) { repository.deleteSong("song") }
        assertEquals(SongDeletionOutcome.CleanupUncertain, outcome)
        assertNull(database.songDao().getSongById("song"))
        assertTrue(database.playlistDao().getSongIdsForPlaylist("playlist").isEmpty())
        assertEquals(1, database.playlistDao().getPlaylistCount())
        assertFalse(File(context.filesDir, "art/art_song.jpg").exists())
        assertBackupAttempt(BackupReason.DELETE_SONG)
        assertEquals(1, PreferencesManager(context).destructiveActionsSinceBackup)
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun playlistDeletionIsRejectedWhenRestoreBeginsAfterBackup() = runBlocking {
        assertRestoreWins(BackupReason.DELETE_PLAYLIST) { repository.deletePlaylist("playlist") }
    }

    @Test fun songDeletionIsRejectedWhenRestoreBeginsAfterBackup() = runBlocking {
        assertRestoreWins(BackupReason.DELETE_SONG) { repository.deleteSong("song") }
    }

    private suspend fun assertRestoreWins(reason: String, delete: suspend () -> Unit) {
        var restoreStarted = false
        context.afterBackup = {
            // BackupOutcomeStore writes after createBackup's own permit has been released.
            // Enter restore synchronously here so the race is deterministic, without sleeps
            // or a production test hook. No database replacement is performed by this test.
            assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
            runBlocking { RestoreCoordinator.exclusive { restoreStarted = true } }
        }
        withTimeout(3_000) {
            try {
                delete()
                fail("Deletion must reacquire admission after backup")
            } catch (expected: IllegalStateException) {
                assertEquals("Database operation is unavailable during restore", expected.message)
            }
        }
        assertTrue(restoreStarted)
        assertEquals(RestoreCoordinator.Mode.RESTORE_PREPARING, RestoreCoordinator.currentMode())
        assertBackupAttempt(reason)
        // Direct reads of this test-owned Room instance prove no mutation or cleanup ran.
        assertNotNull(database.songDao().getSongById("song"))
        assertEquals(listOf("playlist"), database.playlistDao().getAllPlaylists().first().map { it.id })
        assertEquals(listOf("song"), database.playlistDao().getSongIdsForPlaylist("playlist"))
        assertTrue(File(context.filesDir, "art/art_song.jpg").exists())
        assertEquals(0, PreferencesManager(context).destructiveActionsSinceBackup)
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    private fun assertBackupAttempt(reason: String) {
        val outcome = requireNotNull(BackupOutcomeStore.lastBackupOutcome.value)
        assertEquals(reason, outcome.reason)
        // No SAF folder is configured. The real backup must finish its drain and return this
        // best-effort failure, which must not prevent deletion in NORMAL mode.
        assertFalse(outcome.succeeded)
        assertEquals("No backup folder selected", outcome.failureMessage)
    }

    /** Keeps all preference/art writes separate from the installed app's library. */
    private class DeletionTestContext(base: Context) : ContextWrapper(base) {
        private val prefix = "deletion-test-${UUID.randomUUID()}"
        private val preferenceNames = mutableSetOf<String>()
        private val testFiles = File(base.cacheDir, prefix).also { it.mkdirs() }
        var afterBackup: (() -> Unit)? = null

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFiles

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames += isolatedName
            val prefs = super.getSharedPreferences(isolatedName, mode)
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        private var backupOutcomeWritten = false
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            backupOutcomeWritten = key == "last_backup_attempt_failure_message"
                            return this
                        }
                        override fun apply() {
                            editor.apply()
                            if (backupOutcomeWritten) afterBackup?.invoke()
                        }
                    }
                }
            }
        }

        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(testFiles.parentFile == baseContext.cacheDir)
            testFiles.deleteRecursively()
        }
    }
}
