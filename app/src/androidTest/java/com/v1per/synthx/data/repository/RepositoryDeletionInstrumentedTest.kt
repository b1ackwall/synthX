package com.v1per.synthx.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.AudioFileDeletion
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
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult
import com.v1per.synthx.data.scanner.KnownAudioRoot
import com.v1per.synthx.data.scanner.graduateSong
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the public repository methods and real backup drain, not a copy of their ordering. */
@RunWith(AndroidJUnit4::class)
class RepositoryDeletionInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private lateinit var context: DeletionTestContext
    private lateinit var database: SynthXDatabase
    private lateinit var repository: SongRepositoryImpl
    private lateinit var exports: PlaylistExportManager

    @Before fun setUp() = runBlocking {
        RestoreStartup.await()
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
        context = DeletionTestContext(ApplicationProvider.getApplicationContext())
        DestructiveActionTracker.resetForTest(context)
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        exports = PlaylistExportManager(
            context, database, database.playlistDao(), database.songDao(), database.artistDao(), database.albumDao(),
        )
        repository = SongRepositoryImpl(
            context, database, database.songDao(), database.artistDao(), database.albumDao(),
            database.playlistDao(), database.queueDao(), database.pendingCleanupDao(),
            FolderScanner(
                context, database, database.songDao(), database.artistDao(), database.albumDao(),
                database.songArtistDao(), exports,
            ),
            BackupManager(context), exports,
            destructiveFileCheck = { _, _ -> DestructiveFilePreflightResult.Ambiguous },
            replacementFileCheck = { _, _ -> ReplacementFilePreflightResult.Ambiguous },
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
        // Before cleanUp(): resetting afterwards would recreate the isolated preference file.
        DestructiveActionTracker.resetForTest(context)
        context.cleanUp()
        RestoreCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
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

    @Test fun deletePreviewNamesMembershipsAndDetectsOnlyMemberPlaylist() = runBlocking {
        database.songDao().upsertSong(
            SongEntity(
                id = "other", title = "Other", albumId = null, duration = 1,
                filePath = "test://other", albumArtUri = null, year = null, dateAdded = 2,
            ),
        )
        database.playlistDao().insertPlaylist(
            PlaylistEntity("shared", "Shared", null, null, 1, 1, 1, null, 120, 43L),
        )
        database.playlistDao().insertPlaylistSong(PlaylistSongEntity("shared", "song", 0, 1))
        database.playlistDao().insertPlaylistSong(PlaylistSongEntity("shared", "other", 1, 2))

        assertEquals(
            DeleteSongPreview(
                listOf(
                    DeleteSongPlaylistImpact("Playlist", willBecomeEmpty = true),
                    DeleteSongPlaylistImpact("Shared", willBecomeEmpty = false),
                )
            ),
            repository.previewDeleteSong("song"),
        )
    }

    @Test fun audioFileRetryReattemptsCapturedPathAndCanSucceed() = runBlocking {
        val attemptedPaths = mutableListOf<String>()
        val outcomes = ArrayDeque(
            listOf(AudioFileDeletion.Failed, AudioFileDeletion.ConfirmedAbsent)
        )
        val retryingRepository = repositoryWithPreflight(
            result = DestructiveFilePreflightResult.Ambiguous,
            audioFileDelete = { path ->
                attemptedPaths += path
                outcomes.removeFirst()
            },
        )

        assertEquals(
            SongDeletionOutcome.FileRemained,
            retryingRepository.retryAudioFileDeletion("content://captured/audio"),
        )
        assertEquals(
            SongDeletionOutcome.Deleted,
            retryingRepository.retryAudioFileDeletion("content://captured/audio"),
        )
        assertEquals(
            listOf("content://captured/audio", "content://captured/audio"),
            attemptedPaths,
        )
    }

    @Test fun successfulSongDeletionSchedulesPlaylistExport() = runBlocking {
        var exportRequests = 0
        val schedulingRepository = repositoryWithPreflight(
            result = DestructiveFilePreflightResult.Ambiguous,
            schedulePlaylistExport = { exportRequests++ },
        )

        schedulingRepository.deleteSong("song")

        assertEquals(1, exportRequests)
    }

    @Test fun playlistExportSchedulingFailureMakesSuccessfulDeleteUncertain() = runBlocking {
        val schedulingRepository = repositoryWithPreflight(
            result = DestructiveFilePreflightResult.Ambiguous,
            schedulePlaylistExport = { error("export scheduler unavailable") },
        )

        // A missing row is an idempotently successful delete with no audio cleanup uncertainty,
        // which isolates the scheduling failure's effect on the returned outcome.
        val outcome = schedulingRepository.deleteSong("already-missing")

        assertEquals(SongDeletionOutcome.CleanupUncertain, outcome)
    }

    @Test fun readOnlyOwningRootBlocksSongDeletionWithoutChangingTheRow() = runBlocking {
        val blockedRepository = repositoryWithPreflight(
            DestructiveFilePreflightResult.GrantNotWritable(KnownAudioRoot.Music),
        )

        val outcome = blockedRepository.deleteSong("song")

        assertNotNull(database.songDao().getSongById("song"))
        assertEquals(
            SongDeletionOutcome.Blocked(
                DestructiveFilePreflightResult.GrantNotWritable(KnownAudioRoot.Music),
            ),
            outcome,
        )
        assertEquals(listOf("song"), database.playlistDao().getSongIdsForPlaylist("playlist"))
        assertTrue(File(context.filesDir, "art/art_song.jpg").exists())
        assertEquals(0, PreferencesManager(context).destructiveActionsSinceBackup)
    }

    @Test fun externallyReplacedAudioBlocksSongDeletionWithoutChangingTheRow() = runBlocking {
        val blockedRepository = repositoryWithPreflight(
            DestructiveFilePreflightResult.IdentityMismatch,
        )

        val outcome = blockedRepository.deleteSong("song")

        assertNotNull(database.songDao().getSongById("song"))
        assertEquals(
            SongDeletionOutcome.Blocked(DestructiveFilePreflightResult.IdentityMismatch),
            outcome,
        )
        assertEquals(listOf("song"), database.playlistDao().getSongIdsForPlaylist("playlist"))
        assertTrue(File(context.filesDir, "art/art_song.jpg").exists())
        assertEquals(0, PreferencesManager(context).destructiveActionsSinceBackup)
    }

    @Test fun confirmedGonePreflightDoesNotBlockExistingDeleteBehavior() = runBlocking {
        val nonBlockingRepository = repositoryWithPreflight(
            DestructiveFilePreflightResult.SourceGone,
        )

        val outcome = nonBlockingRepository.deleteSong("song")

        assertEquals(SongDeletionOutcome.CleanupUncertain, outcome)
        assertNull(database.songDao().getSongById("song"))
    }

    @Test fun playlistDeletionIsRejectedWhenRestoreBeginsAfterBackup() = runBlocking {
        assertRestoreWins(BackupReason.DELETE_PLAYLIST) { repository.deletePlaylist("playlist") }
    }

    @Test fun songDeletionIsRejectedWhenRestoreBeginsAfterBackup() = runBlocking {
        assertRestoreWins(BackupReason.DELETE_SONG) { repository.deleteSong("song") }
    }

    /**
     * Delete-during-graduation, lock layer in isolation: the song's lock is held the way
     * graduation holds it, but with no restore permit, so the backup's finite drain can't be what
     * holds the delete back — only deleteSong's own song lock can.
     */
    @Test fun songDeletionWaitsForTheSongLockHeldByAGraduation() = runBlocking {
        val graduationHolding = CompletableDeferred<Unit>()
        val finishGraduation = CompletableDeferred<Unit>()
        val graduation = async(Dispatchers.IO) {
            FileOperationCoordinator.withSongLock("song") {
                graduationHolding.complete(Unit)
                finishGraduation.await()
                // Graduation's own DB step still sees its row: the delete hasn't run.
                assertNotNull(database.songDao().getSongById("song"))
            }
        }
        withTimeout(3_000) { graduationHolding.await() }

        val deletion = async(Dispatchers.IO) { repository.deleteSong("song") }
        // Backup finished and the delete is now queued on the song lock.
        withTimeout(3_000) {
            while (FileOperationCoordinator.songLockRefCountForTest("song") < 2) delay(10)
        }
        assertFalse(deletion.isCompleted)
        assertNotNull(database.songDao().getSongById("song"))
        assertEquals(listOf("song"), database.playlistDao().getSongIdsForPlaylist("playlist"))

        finishGraduation.complete(Unit)
        withTimeout(3_000) { graduation.await() }
        assertEquals(SongDeletionOutcome.CleanupUncertain, withTimeout(3_000) { deletion.await() })
        assertNull(database.songDao().getSongById("song"))
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    /**
     * Delete-during-graduation against the real graduateSong, held open inside its song lock and
     * restore permit (see SongGraduationCoordinationInstrumentedTest for how). Proves the full
     * acquisition order composes without deadlock: the delete's backup waits out graduation's
     * permit, then takes the song lock, then deletes.
     */
    @Test fun songDeletionDuringRealGraduationCompletesAfterItWithoutDeadlock() = runBlocking {
        val graduationHolding = CompletableDeferred<Unit>()
        val finishGraduation = CompletableDeferred<Unit>()
        val graduation = async(Dispatchers.IO) {
            graduateSong(
                context, database, database.songDao(), "song",
                "content://com.v1per.synthx.test.nonexistent/tree/music".toUri(),
            ) { stage ->
                if (stage is GraduationStage.Failed) {
                    graduationHolding.complete(Unit)
                    finishGraduation.await()
                }
            }
        }
        withTimeout(3_000) { graduationHolding.await() }
        // Held open: graduation itself owns the song lock and one restore permit.
        assertEquals("graduation holds the song lock", 1, FileOperationCoordinator.songLockRefCountForTest("song"))
        assertEquals("graduation holds a restore permit", 1, RestoreCoordinator.activeOperationCountForTest())

        val deletion = async(Dispatchers.IO) { repository.deleteSong("song") }
        delay(300)
        assertFalse(deletion.isCompleted)
        assertNotNull(database.songDao().getSongById("song"))

        finishGraduation.complete(Unit)
        withTimeout(3_000) { graduation.await() }
        assertEquals(SongDeletionOutcome.CleanupUncertain, withTimeout(3_000) { deletion.await() })
        assertNull(database.songDao().getSongById("song"))
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
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

    private fun repositoryWithPreflight(
        result: DestructiveFilePreflightResult,
        schedulePlaylistExport: () -> Unit = exports::scheduleExport,
        audioFileDelete: (String) -> AudioFileDeletion = { AudioFileDeletion.Unknown },
    ): SongRepositoryImpl = SongRepositoryImpl(
        context, database, database.songDao(), database.artistDao(), database.albumDao(),
        database.playlistDao(), database.queueDao(), database.pendingCleanupDao(),
        FolderScanner(
            context, database, database.songDao(), database.artistDao(), database.albumDao(),
            database.songArtistDao(), exports,
        ),
        BackupManager(context), exports,
        destructiveFileCheck = { _, _ -> result },
        replacementFileCheck = { _, _ -> ReplacementFilePreflightResult.Ambiguous },
        schedulePlaylistExport = schedulePlaylistExport,
        audioFileDelete = audioFileDelete,
    )

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
