package com.v1per.synthx.playback

import android.net.Uri
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.repository.AdmittedQueuePersistenceRepository
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistConflictResolution
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.ReplaceSongPreview
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongDeletionOutcome
import com.v1per.synthx.data.repository.SongRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QueuePersistenceB1Test {
    private class QueueRepo : SongRepository, AdmittedQueuePersistenceRepository {
        val persisted = mutableListOf<List<String>>()
        var gate: CompletableDeferred<Unit>? = null
        var failWrites = false
        var snapshot = QueueSnapshot(emptyList(), 0, null)
        val started = CompletableDeferred<Unit>()
        override suspend fun setQueueState(
            slots: List<QueueSlotRecord>,
            currentSlotId: String?,
            positionMs: Long,
        ) {
            started.complete(Unit); gate?.await(); persisted += slots.map { it.slotId }
            if (failWrites) error("database failure")
        }
        override suspend fun setBaselineQueueSlots(slots: List<QueueSlotRecord>) {
            started.complete(Unit); gate?.await(); persisted += slots.map { it.slotId }
            if (failWrites) error("database failure")
        }
        override suspend fun setCursorAlreadyAdmitted(currentSlotId: String?, positionMs: Long) {}
        override suspend fun setShuffleEnabledAlreadyAdmitted(enabled: Boolean) {}
        override suspend fun setRepeatModeAlreadyAdmitted(mode: Int) {}
        override fun getSongs(): Flow<List<Song>> = emptyFlow(); override fun getArtists(): Flow<List<Artist>> = emptyFlow()
        override fun getSongsForArtist(artistId: String): Flow<List<Song>> = emptyFlow(); override fun getAlbums(): Flow<List<Album>> = emptyFlow()
        override fun getSongsForAlbum(albumId: String): Flow<List<Song>> = emptyFlow()
        override fun getMissingSongs(): Flow<List<MissingSongInfo>> = emptyFlow(); override fun getMissingSongCount(): Flow<Int> = emptyFlow()
        override suspend fun findMissingArtSongIds(): List<String> = emptyList()
        override suspend fun recoverPaletteData(songIds: List<String>) =
            com.v1per.synthx.data.scanner.PaletteRecoveryResult(0, 0, 0)
        override suspend fun recoverMissingArt(songIds: List<String>?) =
            com.v1per.synthx.data.scanner.ArtRecoveryResult(checked = 0, recovered = 0, skipped = 0)
        override suspend fun deleteSong(id: String) = SongDeletionOutcome.Deleted
        override suspend fun previewReplaceSong(oldSongId: String, newSongId: String): ReplaceSongPreview? = null
        override suspend fun replaceSong(oldSongId: String, newSongId: String, conflictResolution: PlaylistConflictResolution): ReplaceSongResult = ReplaceSongResult.SameSong
        override fun getStarredSongs(): Flow<List<Song>> = emptyFlow(); override fun getStarredSongCount(): Flow<Int> = emptyFlow()
        override fun getRecentSongs(limit: Int): Flow<List<Song>> = emptyFlow()
        override suspend fun dismissFromStarred(songId: String) {}; override suspend fun isInAnyPlaylist(songId: String) = false; override suspend fun checkRootLibraryOrganization(musicFolderUri: Uri) = null
        override fun graduateSong(songId: String, rootMusicFolderUri: Uri, rootNewArrivalsFolderUri: Uri?): Flow<com.v1per.synthx.data.scanner.GraduationStage> = emptyFlow()
        override fun getAllGroups(): Flow<List<PlaylistGroup>> = emptyFlow(); override suspend fun createGroup(name: String) {}; override suspend fun renameGroup(id: String,name: String) {}; override suspend fun deleteGroup(id: String) {}
        override fun getAllPlaylists(): Flow<List<Playlist>> = emptyFlow(); override fun getUngroupedPlaylists(): Flow<List<Playlist>> = emptyFlow(); override fun getPlaylistsForGroup(groupId: String): Flow<List<Playlist>> = emptyFlow()
        override suspend fun createPlaylist(name: String, groupId: String?) = ""; override suspend fun renamePlaylist(id: String,name: String) {}; override suspend fun movePlaylistToGroup(id: String,groupId: String?) {}; override suspend fun deletePlaylist(id: String) {}
        override fun getSongsForPlaylist(playlistId: String): Flow<List<Song>> = emptyFlow(); override suspend fun getPlaylistIdsForSong(songId: String) = emptyList<String>(); override suspend fun addSongToPlaylist(playlistId: String,songId: String) = false; override suspend fun removeSongFromPlaylist(playlistId: String,songId: String) {}
        override suspend fun getSongsByIds(ids: List<String>) = emptyList<Song>()
        override suspend fun getQueueSlots() = emptyList<QueueSlot>()
        override suspend fun getQueueSnapshot() = snapshot
        override suspend fun getBaselineQueueSlots() = emptyList<QueueSlot>()
    }
    private fun song(id: String) = Song(id, id, "", "", 0L, "")
    private fun slots(vararg ids: String) = ids.map { QueueSlot(slotId = it, song = song(it)) }
    private fun records(vararg ids: String) = ids.map { QueueSlotRecord(slotId = it, songId = it) }
    private fun reset() = RestoreCoordinator.resetForTest()

    @Test fun acceptedWriteDrainsAfterPreparation() = runTest {
        reset(); val repo = QueueRepo(); repo.gate = CompletableDeferred(); val queue = QueuePersistence(repo)
        queue.saveQueueRecords(records("a"), "a", 0L); repo.started.await()
        val restore = async { RestoreCoordinator.exclusive { queue.quiesceForRestore(); RestoreCoordinator.awaitDrained() } }
        yield()
        repo.gate!!.complete(Unit); withTimeout(2_000) { restore.await() }
        assertEquals(listOf(listOf("a")), repo.persisted); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun multipleAcceptedWritesDrainFifoAndReleasePermits() = runTest {
        reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo)
        queue.saveQueueRecords(records("a"), "a", 0L); queue.saveQueueRecords(records("b"), "b", 0L); queue.quiesceForRestore()
        assertEquals(listOf(listOf("a"), listOf("b")), repo.persisted); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun closedRejectsNewQueueWriteWithoutPermitLeak() = runTest {
        reset(); RestoreCoordinator.markClosed(); val repo = QueueRepo(); val queue = QueuePersistence(repo)
        queue.saveQueueRecords(records("a"), "a", 0L); queue.quiesceForRestore()
        assertTrue(repo.persisted.isEmpty()); assertEquals(0, RestoreCoordinator.activeOperationCountForTest()); reset()
    }

    @Test fun writePreparationRaceIsPersistedOrRejectedWithoutLeak() = runTest {
        repeat(12) { index ->
            reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo)
            val preparation = async { RestoreCoordinator.exclusive { queue.quiesceForRestore(); RestoreCoordinator.awaitDrained() } }
            // The coordinator transition and save race through their real synchronization.
            queue.saveQueueRecords(records("$index"), "$index", 0L)
            preparation.await()
            assertTrue(repo.persisted.isEmpty() || repo.persisted == listOf(listOf("$index")))
            assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
            reset()
        }
    }

    @Test fun unavailableEnqueueAfterPermitAcquisitionReleasesPermit() = runTest {
        reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo)
        queue.release()
        // saveQueueRecords acquires its permit first, then sees the closed admission gate and releases it.
        queue.saveQueueRecords(records("a"), "a", 0L)
        queue.quiesceForRestore()
        assertTrue(repo.persisted.isEmpty()); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun mutationFailureStillDrainsAndReleasesPermit() = runTest {
        reset(); val repo = QueueRepo().also { it.failWrites = true }; val queue = QueuePersistence(repo)
        queue.saveQueueRecords(records("a"), "a", 0L); queue.quiesceForRestore()
        assertEquals(listOf(listOf("a")), repo.persisted); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun allLiveInstancesDrainAndReleasedInstanceUnregisters() = runTest {
        reset(); val first = QueueRepo(); val second = QueueRepo()
        val one = QueuePersistence(first); val two = QueuePersistence(second)
        assertTrue(QueuePersistence.activeInstanceCountForTest() >= 2)
        one.saveQueueRecords(records("one"), "one", 0L); two.saveQueueRecords(records("two"), "two", 0L)
        QueuePersistence.quiesceAllForRestore()
        assertEquals(listOf(listOf("one")), first.persisted); assertEquals(listOf(listOf("two")), second.persisted)
        assertEquals(0, QueuePersistence.activeInstanceCountForTest()); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
        reset()
    }

    @Test fun releasedInstanceNeverAcceptsAgainOrBlocksGlobalQuiesce() = runTest {
        reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo)
        queue.release(); queue.quiesceForRestore(); assertEquals(0, QueuePersistence.activeInstanceCountForTest())
        queue.saveQueueRecords(records("late"), "late", 0L); QueuePersistence.quiesceAllForRestore()
        assertTrue(repo.persisted.isEmpty()); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun restoreResolvesTheExactDuplicateSlotCursor() = runTest {
        reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo)
        val duplicate = song("same-content")
        repo.snapshot = QueueSnapshot(
            slots = listOf(QueueSlot("slot-a", duplicate), QueueSlot("slot-b", duplicate)),
            storedSlotCount = 2,
            cursor = QueueCursor("slot-b", 4_321L, shuffleEnabled = true, repeatMode = 2),
        )

        val restored = assertNotNull(queue.loadQueue())
        assertEquals(1, restored.startIndex)
        assertEquals(4_321L, restored.positionMs)
        assertTrue(restored.shuffleEnabled)
        assertEquals(2, restored.repeatMode)
        queue.quiesceForRestore()
    }

    @Test fun restoreFallsBackToFirstSlotWhenSavedSlotNoLongerExists() = runTest {
        reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo)
        repo.snapshot = QueueSnapshot(
            slots = slots("survivor"),
            storedSlotCount = 1,
            cursor = QueueCursor("deleted-slot", 4_321L, shuffleEnabled = false, repeatMode = 0),
        )

        val restored = assertNotNull(queue.loadQueue())
        assertEquals(0, restored.startIndex)
        assertEquals(0L, restored.positionMs)
        queue.quiesceForRestore()
    }
}
