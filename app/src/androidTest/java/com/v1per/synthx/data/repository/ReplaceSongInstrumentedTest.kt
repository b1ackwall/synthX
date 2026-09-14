package com.v1per.synthx.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult
import com.v1per.synthx.preferences.DestructiveActionTracker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real [SongRepositoryImpl.replaceSong] against a real Room database — the identity
 * transplant is almost entirely SQL semantics (composite primary keys, REPLACE-on-conflict, FK
 * CASCADE), so a fake DAO would prove nothing about the part that can actually be wrong.
 *
 * Fixture: three playlists.
 *  - `only-old`   holds the old song at position 5 — a plain transfer, no conflict.
 *  - `both`       holds the old song at position 1 and the new song at position 9 — the conflict.
 *  - `only-new`   holds the new song at position 3 — outside the operation entirely.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceSongInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: SynthXDatabase
    private lateinit var repository: SongRepositoryImpl
    private lateinit var exports: PlaylistExportManager

    private val oldAddedAt = 1_000L
    private val newAddedAt = 2_000L

    @Before fun setUp() = runBlocking {
        RestoreStartup.await()
        RestoreCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
        DestructiveActionTracker.resetForTest()
        context = ReplaceTestContext(ApplicationProvider.getApplicationContext())
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        exports = PlaylistExportManager(
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
            destructiveFileCheck = { _, _ -> DestructiveFilePreflightResult.Ambiguous },
            replacementFileCheck = { _, _ -> ReplacementFilePreflightResult.Ambiguous },
        )

        database.songDao().upsertSong(
            SongEntity(
                id = "old", title = "Old", albumId = null, duration = 1,
                filePath = "test://old", albumArtUri = null, year = null,
                dateAdded = 111L, isStarred = false,
            ),
        )
        database.songDao().upsertSong(
            SongEntity(
                id = "new", title = "New", albumId = null, duration = 1,
                filePath = "test://new", albumArtUri = null, year = null,
                dateAdded = 999L, isStarred = true,
            ),
        )
        listOf("only-old", "both", "only-new").forEach { id ->
            database.playlistDao().insertPlaylist(
                PlaylistEntity(id, "Playlist $id", null, null, 0, 1, 1, null, 120, 42L),
            )
        }
        val dao = database.playlistDao()
        dao.insertPlaylistSong(PlaylistSongEntity("only-old", "old", 5, oldAddedAt))
        dao.insertPlaylistSong(PlaylistSongEntity("both", "old", 1, oldAddedAt))
        dao.insertPlaylistSong(PlaylistSongEntity("both", "new", 9, newAddedAt))
        dao.insertPlaylistSong(PlaylistSongEntity("only-new", "new", 3, newAddedAt))
    }

    @After fun tearDown() {
        database.close()
        (context as ReplaceTestContext).cleanUp()
        RestoreCoordinator.resetForTest()
        BackupOutcomeStore.resetForTest()
        DestructiveActionTracker.resetForTest()
    }

    private suspend fun membership(playlistId: String, songId: String) =
        database.playlistDao().getPlaylistMembershipsForSong(songId)
            .firstOrNull { it.playlistId == playlistId }

    private suspend fun replace(resolution: PlaylistConflictResolution) =
        withTimeout(10_000) {
            val preview = repository.previewReplaceSong("old", "new") as ReplaceSongPreviewResult.Ready
            repository.replaceSong("old", "new", resolution, preview.membershipSnapshot)
        }

    @Test fun keepOldMovesTheReplacementIntoTheOldSongsSlot() = runBlocking {
        val result = replace(PlaylistConflictResolution.KeepOld)
        assertEquals(
            ReplaceSongResult.Success(
                oldAudioCleanup = SongDeletionOutcome.CleanupUncertain,
            ),
            result,
        )

        // Non-conflicting transfer: same position, and the membership's own added_at inherited.
        assertEquals(5, membership("only-old", "new")?.position)
        assertEquals(oldAddedAt, membership("only-old", "new")?.addedAt)
        // Conflict: the old song's slot wins, replacing the new song's own row rather than
        // duplicating it — one row, at position 1, not two.
        assertEquals(1, membership("both", "new")?.position)
        assertEquals(
            listOf("new"),
            database.playlistDao().getSongIdsForPlaylist("both"),
        )
        // A playlist only the replacement was in is left exactly as it was.
        assertEquals(3, membership("only-new", "new")?.position)
        assertEquals(newAddedAt, membership("only-new", "new")?.addedAt)
    }

    @Test fun mergeBehavesIdenticallyToKeepOld() = runBlocking {
        replace(PlaylistConflictResolution.Merge)
        // Documented equivalence: a playlist_songs row carries no data to reconcile beyond
        // position, so "merge" and "the old song's place wins" are the same write.
        assertEquals(5, membership("only-old", "new")?.position)
        assertEquals(1, membership("both", "new")?.position)
        assertEquals(3, membership("only-new", "new")?.position)
        assertEquals(listOf("new"), database.playlistDao().getSongIdsForPlaylist("both"))
    }

    @Test fun keepNewLeavesTheReplacementsOwnPositionAlone() = runBlocking {
        replace(PlaylistConflictResolution.KeepNew)
        // Non-conflicting playlists still transfer unconditionally — the resolution only ever
        // applies to playlists both songs were already in.
        assertEquals(5, membership("only-old", "new")?.position)
        // Conflict: the replacement stays at 9, and the old song's row is simply dropped.
        assertEquals(9, membership("both", "new")?.position)
        assertEquals(newAddedAt, membership("both", "new")?.addedAt)
        assertEquals(listOf("new"), database.playlistDao().getSongIdsForPlaylist("both"))
    }

    @Test fun replacementInheritsDateAddedAndIsUnstarred() = runBlocking {
        replace(PlaylistConflictResolution.KeepOld)
        val replacement = requireNotNull(database.songDao().getSongById("new"))
        assertEquals(111L, replacement.dateAdded)
        // It now belongs to playlists, so it is by definition organized — regardless of the old
        // song's own (false) starred value, which is never copied.
        assertFalse(replacement.isStarred)
    }

    @Test fun replacementKeepsItsOwnStarredValueWhenItJoinsNoPlaylist() = runBlocking {
        // Strip every membership first, so the replacement ends up in nothing at all.
        listOf("only-old", "both", "only-new").forEach { playlistId ->
            database.playlistDao().removePlaylistSong(playlistId, "old")
            database.playlistDao().removePlaylistSong(playlistId, "new")
        }
        replace(PlaylistConflictResolution.KeepOld)
        val replacement = requireNotNull(database.songDao().getSongById("new"))
        assertTrue("Starred must be left untouched, not copied from the old song", replacement.isStarred)
        assertEquals(111L, replacement.dateAdded)
    }

    @Test fun oldSongIsCompletelyDeleted() = runBlocking {
        File(context.filesDir, "art").mkdirs()
        File(context.filesDir, "art/art_old.jpg").writeText("test art")
        replace(PlaylistConflictResolution.KeepOld)

        assertNull(database.songDao().getSongById("old"))
        assertNotNull(database.songDao().getSongById("new"))
        assertTrue(database.playlistDao().getPlaylistMembershipsForSong("old").isEmpty())
        assertFalse(File(context.filesDir, "art/art_old.jpg").exists())
        // Same destructive-action accounting a plain delete does — one stale-backup warning.
        assertEquals(1, DestructiveActionTracker.count.value)
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun rejectsReplacingASongWithItself() = runBlocking {
        assertEquals(
            ReplaceSongResult.SameSong,
            repository.replaceSong(
                "old",
                "old",
                PlaylistConflictResolution.KeepOld,
                ReplaceSongMembershipSnapshot(emptyList(), emptyList()),
            ),
        )
        assertNotNull(database.songDao().getSongById("old"))
        // Rejected before the backup, so nothing at all was attempted.
        assertNull(BackupOutcomeStore.lastBackupOutcome.value)
    }

    @Test fun missingSongsAreReportedAndChangeNothing() = runBlocking {
        assertEquals(ReplaceSongResult.OldSongMissing, replaceIds("ghost", "new"))
        assertEquals(ReplaceSongResult.NewSongMissing, replaceIds("old", "ghost"))
        assertNotNull(database.songDao().getSongById("old"))
        assertEquals(1, membership("both", "old")?.position)
        assertEquals(9, membership("both", "new")?.position)
    }

    @Test fun vanishedReplacementFileAbortsBeforeTouchingOldSongOrMemberships() = runBlocking {
        val oldAudio = File(context.filesDir, "old-audio.mp3").also { it.writeText("old audio") }
        val oldRow = checkNotNull(database.songDao().getSongById("old"))
        database.songDao().upsertSong(oldRow.copy(filePath = oldAudio.toURI().toString()))
        val guardedRepository = repositoryWithPreflights(
            oldResult = DestructiveFilePreflightResult.Passed,
            newResult = ReplacementFilePreflightResult.SourceGone,
        )

        val preview = guardedRepository.previewReplaceSong("old", "new") as ReplaceSongPreviewResult.Ready
        val result = guardedRepository.replaceSong(
            "old", "new", PlaylistConflictResolution.KeepOld, preview.membershipSnapshot,
        )

        assertEquals(
            ReplaceSongResult.NewSongUnusable(ReplacementFilePreflightResult.SourceGone),
            result,
        )
        assertNotNull(database.songDao().getSongById("old"))
        assertNotNull(database.songDao().getSongById("new"))
        assertTrue(oldAudio.exists())
        assertEquals(5, membership("only-old", "old")?.position)
        assertNull(membership("only-old", "new"))
        assertEquals(1, membership("both", "old")?.position)
        assertEquals(9, membership("both", "new")?.position)
    }

    @Test fun changedReplacementFileAbortsBeforePlaylistTransplant() = runBlocking {
        val guardedRepository = repositoryWithPreflights(
            oldResult = DestructiveFilePreflightResult.Passed,
            newResult = ReplacementFilePreflightResult.IdentityMismatch,
        )

        val preview = guardedRepository.previewReplaceSong("old", "new") as ReplaceSongPreviewResult.Ready
        val result = guardedRepository.replaceSong(
            "old", "new", PlaylistConflictResolution.KeepOld, preview.membershipSnapshot,
        )

        assertEquals(
            ReplaceSongResult.NewSongUnusable(ReplacementFilePreflightResult.IdentityMismatch),
            result,
        )
        assertNotNull(database.songDao().getSongById("old"))
        assertEquals(5, membership("only-old", "old")?.position)
        assertNull(membership("only-old", "new"))
    }

    @Test fun goneOldFileAndAmbiguousReplacementDoNotBlockExistingReplaceBehavior() = runBlocking {
        val nonBlockingRepository = repositoryWithPreflights(
            oldResult = DestructiveFilePreflightResult.SourceGone,
            newResult = ReplacementFilePreflightResult.Ambiguous,
        )

        val preview = nonBlockingRepository.previewReplaceSong("old", "new") as ReplaceSongPreviewResult.Ready
        val result = nonBlockingRepository.replaceSong(
            "old", "new", PlaylistConflictResolution.KeepOld, preview.membershipSnapshot,
        )

        assertTrue(result is ReplaceSongResult.Success)
        assertNull(database.songDao().getSongById("old"))
        assertNotNull(database.songDao().getSongById("new"))
        assertEquals(5, membership("only-old", "new")?.position)
    }

    private suspend fun replaceIds(oldId: String, newId: String) = withTimeout(10_000) {
        repository.replaceSong(
            oldId,
            newId,
            PlaylistConflictResolution.KeepOld,
            ReplaceSongMembershipSnapshot(emptyList(), emptyList()),
        )
    }

    @Test fun newConflictAfterPreviewAbortsWithoutApplyingTheHiddenDefault() = runBlocking {
        val preview = repository.previewReplaceSong("old", "new") as ReplaceSongPreviewResult.Ready
        database.playlistDao().insertPlaylistSong(
            PlaylistSongEntity("only-old", "new", 12, newAddedAt),
        )

        val result = repository.replaceSong(
            "old", "new", PlaylistConflictResolution.KeepOld, preview.membershipSnapshot,
        )

        assertEquals(ReplaceSongResult.PreviewStale, result)
        assertNotNull(database.songDao().getSongById("old"))
        assertEquals(5, membership("only-old", "old")?.position)
        assertEquals(12, membership("only-old", "new")?.position)
    }

    @Test fun removedMembershipAfterPreviewAbortsWithoutAnyWrites() = runBlocking {
        val preview = repository.previewReplaceSong("old", "new") as ReplaceSongPreviewResult.Ready
        database.playlistDao().removePlaylistSong("only-old", "old")

        val result = repository.replaceSong(
            "old", "new", PlaylistConflictResolution.KeepOld, preview.membershipSnapshot,
        )

        assertEquals(ReplaceSongResult.PreviewStale, result)
        assertNotNull(database.songDao().getSongById("old"))
        assertNull(membership("only-old", "new"))
        assertEquals(1, membership("both", "old")?.position)
        assertEquals(9, membership("both", "new")?.position)
    }

    @Test fun previewMembershipReadsComeFromOneTransactionSnapshot() = runBlocking {
        val reachedBetweenMembershipReads = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        val transactionalRepository = repositoryWithPreflights(
            oldResult = DestructiveFilePreflightResult.Ambiguous,
            newResult = ReplacementFilePreflightResult.Ambiguous,
            previewAnalysisCheckpoint = {
                reachedBetweenMembershipReads.complete(Unit)
                writerStarted.await()
                // Without the enclosing transaction this gives the writer ample time to commit
                // between the old- and new-song reads. With it, the writer waits for this preview.
                delay(150)
            },
        )

        val previewDeferred = async(Dispatchers.IO) {
            transactionalRepository.previewReplaceSong("old", "new")
        }
        reachedBetweenMembershipReads.await()
        val writer = async(Dispatchers.IO) {
            writerStarted.complete(Unit)
            database.playlistDao().insertPlaylistSong(
                PlaylistSongEntity("only-old", "new", 12, newAddedAt),
            )
        }

        val preview = previewDeferred.await() as ReplaceSongPreviewResult.Ready
        writer.await()

        assertEquals(listOf("Playlist only-old"), preview.preview.transferringPlaylists)
        assertFalse(preview.preview.conflictingPlaylists.contains("Playlist only-old"))
        assertEquals(12, membership("only-old", "new")?.position)
    }

    private fun repositoryWithPreflights(
        oldResult: DestructiveFilePreflightResult,
        newResult: ReplacementFilePreflightResult,
        previewAnalysisCheckpoint: suspend () -> Unit = {},
    ): SongRepositoryImpl = SongRepositoryImpl(
        context, database, database.songDao(), database.artistDao(), database.albumDao(),
        database.playlistDao(), database.queueDao(),
        FolderScanner(
            context, database, database.songDao(), database.artistDao(), database.albumDao(),
            database.songArtistDao(), exports,
        ),
        BackupManager(context), exports,
        destructiveFileCheck = { _, _ -> oldResult },
        replacementFileCheck = { _, _ -> newResult },
        previewAnalysisCheckpoint = previewAnalysisCheckpoint,
    )

    /**
     * Keeps art *and preference* writes out of the installed app's library, as
     * RepositoryDeletionInstrumentedTest's own context does. The preference isolation is
     * load-bearing, not tidiness: DestructiveActionTracker persists its count, and
     * `resetForTest()` only clears the in-memory StateFlow — so a shared preference file would
     * carry each case's increment into the next one's assertions.
     */
    private class ReplaceTestContext(base: Context) : ContextWrapper(base) {
        private val prefix = "replace-test-${UUID.randomUUID()}"
        private val preferenceNames = mutableSetOf<String>()
        private val testFiles = File(base.cacheDir, prefix).also { it.mkdirs() }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFiles

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames += isolatedName
            return super.getSharedPreferences(isolatedName, mode)
        }

        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(testFiles.parentFile == baseContext.cacheDir)
            testFiles.deleteRecursively()
        }
    }
}
