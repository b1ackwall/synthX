package com.v1per.synthx.playback

import android.net.Uri
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.repository.AdmittedQueuePersistenceRepository
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.scanner.ScanResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueuePersistenceB1Test {
    private class QueueRepo : SongRepository, AdmittedQueuePersistenceRepository {
        val persisted = mutableListOf<List<String>>()
        var gate: CompletableDeferred<Unit>? = null
        var failWrites = false
        val started = CompletableDeferred<Unit>()
        override suspend fun setQueueSongsAlreadyAdmitted(songIds: List<String>) {
            started.complete(Unit); gate?.await(); persisted += songIds
            if (failWrites) error("database failure")
        }
        override suspend fun setBaselineQueueSongsAlreadyAdmitted(songIds: List<String>) = setQueueSongsAlreadyAdmitted(songIds)
        override fun getSongs(): Flow<List<Song>> = emptyFlow(); override fun getArtists(): Flow<List<Artist>> = emptyFlow()
        override fun getSongsForArtist(artistId: String): Flow<List<Song>> = emptyFlow(); override fun getAlbums(): Flow<List<Album>> = emptyFlow()
        override fun getSongsForAlbum(albumId: String): Flow<List<Song>> = emptyFlow()
        override suspend fun scanLibrary(folderUri: Uri, reconcileMissing: Boolean, otherRootUris: List<Uri>, onProgress: suspend (Int, Int) -> Unit) = ScanResult(0,0,0,0)
        override suspend fun verifyLibrary() = 0; override fun getMissingSongs(): Flow<List<MissingSongInfo>> = emptyFlow(); override fun getMissingSongCount(): Flow<Int> = emptyFlow()
        override suspend fun deleteSong(id: String) {}; override fun getStarredSongs(): Flow<List<Song>> = emptyFlow(); override fun getStarredSongCount(): Flow<Int> = emptyFlow()
        override fun getRecentSongs(limit: Int): Flow<List<Song>> = emptyFlow()
        override suspend fun dismissFromStarred(songId: String) {}; override suspend fun isInAnyPlaylist(songId: String) = false; override suspend fun removeOrganizedFromStarred() = 0
        override fun getAllGroups(): Flow<List<PlaylistGroup>> = emptyFlow(); override suspend fun createGroup(name: String) {}; override suspend fun renameGroup(id: String,name: String) {}; override suspend fun deleteGroup(id: String) {}
        override fun getAllPlaylists(): Flow<List<Playlist>> = emptyFlow(); override fun getUngroupedPlaylists(): Flow<List<Playlist>> = emptyFlow(); override fun getPlaylistsForGroup(groupId: String): Flow<List<Playlist>> = emptyFlow()
        override suspend fun createPlaylist(name: String, groupId: String?) = ""; override suspend fun renamePlaylist(id: String,name: String) {}; override suspend fun movePlaylistToGroup(id: String,groupId: String?) {}; override suspend fun deletePlaylist(id: String) {}
        override fun getSongsForPlaylist(playlistId: String): Flow<List<Song>> = emptyFlow(); override suspend fun addSongToPlaylist(playlistId: String,songId: String) = false; override suspend fun removeSongFromPlaylist(playlistId: String,songId: String) {}
        override suspend fun getQueueSongs() = emptyList<Song>(); override suspend fun getQueueSongIds() = emptyList<String>(); override suspend fun setQueueSongs(songIds: List<String>) {}
        override suspend fun getBaselineQueueSongs() = emptyList<Song>(); override suspend fun getBaselineQueueSongIds() = emptyList<String>(); override suspend fun setBaselineQueueSongs(songIds: List<String>) {}
    }
    private fun song(id: String) = Song(id, id, "", "", 0L, "")
    private fun reset() = RestoreCoordinator.resetForTest()

    @Test fun acceptedWriteDrainsAfterPreparation() = runTest {
        reset(); val repo = QueueRepo(); repo.gate = CompletableDeferred(); val queue = QueuePersistence(repo, null)
        queue.saveQueue(listOf(song("a"))); repo.started.await()
        val restore = async { RestoreCoordinator.exclusive { queue.quiesceForRestore(); RestoreCoordinator.awaitDrained() } }
        yield()
        repo.gate!!.complete(Unit); withTimeout(2_000) { restore.await() }
        assertEquals(listOf(listOf("a")), repo.persisted); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun multipleAcceptedWritesDrainFifoAndReleasePermits() = runTest {
        reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo, null)
        queue.saveQueue(listOf(song("a"))); queue.saveQueue(listOf(song("b"))); queue.quiesceForRestore()
        assertEquals(listOf(listOf("a"), listOf("b")), repo.persisted); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun closedRejectsNewQueueWriteWithoutPermitLeak() = runTest {
        reset(); RestoreCoordinator.markClosed(); val repo = QueueRepo(); val queue = QueuePersistence(repo, null)
        queue.saveQueue(listOf(song("a"))); queue.quiesceForRestore()
        assertTrue(repo.persisted.isEmpty()); assertEquals(0, RestoreCoordinator.activeOperationCountForTest()); reset()
    }

    @Test fun writePreparationRaceIsPersistedOrRejectedWithoutLeak() = runTest {
        repeat(12) { index ->
            reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo, null)
            val preparation = async { RestoreCoordinator.exclusive { queue.quiesceForRestore(); RestoreCoordinator.awaitDrained() } }
            // The coordinator transition and save race through their real synchronization.
            queue.saveQueue(listOf(song("$index")))
            preparation.await()
            assertTrue(repo.persisted.isEmpty() || repo.persisted == listOf(listOf("$index")))
            assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
            reset()
        }
    }

    @Test fun unavailableEnqueueAfterPermitAcquisitionReleasesPermit() = runTest {
        reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo, null)
        queue.release()
        // saveQueue acquires its permit first, then sees the closed admission gate and releases it.
        queue.saveQueue(listOf(song("a")))
        queue.quiesceForRestore()
        assertTrue(repo.persisted.isEmpty()); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun mutationFailureStillDrainsAndReleasesPermit() = runTest {
        reset(); val repo = QueueRepo().also { it.failWrites = true }; val queue = QueuePersistence(repo, null)
        queue.saveQueue(listOf(song("a"))); queue.quiesceForRestore()
        assertEquals(listOf(listOf("a")), repo.persisted); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun allLiveInstancesDrainAndReleasedInstanceUnregisters() = runTest {
        reset(); val first = QueueRepo(); val second = QueueRepo()
        val one = QueuePersistence(first, null); val two = QueuePersistence(second, null)
        assertTrue(QueuePersistence.activeInstanceCountForTest() >= 2)
        one.saveQueue(listOf(song("one"))); two.saveQueue(listOf(song("two")))
        QueuePersistence.quiesceAllForRestore()
        assertEquals(listOf(listOf("one")), first.persisted); assertEquals(listOf(listOf("two")), second.persisted)
        assertEquals(0, QueuePersistence.activeInstanceCountForTest()); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
        reset()
    }

    @Test fun releasedInstanceNeverAcceptsAgainOrBlocksGlobalQuiesce() = runTest {
        reset(); val repo = QueueRepo(); val queue = QueuePersistence(repo, null)
        queue.release(); queue.quiesceForRestore(); assertEquals(0, QueuePersistence.activeInstanceCountForTest())
        queue.saveQueue(listOf(song("late"))); QueuePersistence.quiesceAllForRestore()
        assertTrue(repo.persisted.isEmpty()); assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }
}
