package com.v1per.synthx.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SynthXDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistExportConsistencyInstrumentedTest {
    private lateinit var database: SynthXDatabase

    @Before fun setUp() = runBlocking {
        RestoreStartup.await()
        RestoreCoordinator.resetForTest()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        database.playlistDao().insertGroup(
            PlaylistGroupEntity("group", "Group", 0, 1),
        )
        database.playlistDao().insertPlaylist(
            PlaylistEntity("playlist", "Playlist", null, null, 0, 1, 1, null, 120, 42L),
        )
    }

    @After fun tearDown() {
        database.close()
        RestoreCoordinator.resetForTest()
    }

    @Test fun playlistMoveCannotProduceATornExport() = runBlocking {
        val reachedBetweenPlaylistReads = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        val manager = PlaylistExportManager(
            context = ApplicationProvider.getApplicationContext(),
            database = database,
            playlistDao = database.playlistDao(),
            songDao = database.songDao(),
            artistDao = database.artistDao(),
            albumDao = database.albumDao(),
            materializationCheckpoint = {
                reachedBetweenPlaylistReads.complete(Unit)
                writerStarted.await()
                // Without the enclosing transaction, the move commits between the ungrouped
                // and grouped reads. With it, the writer waits for this materialization.
                delay(150)
            },
        )

        val exportDeferred = async(Dispatchers.IO) { manager.materializeExportForTest() }
        reachedBetweenPlaylistReads.await()
        val writer = async(Dispatchers.IO) {
            writerStarted.complete(Unit)
            database.playlistDao().movePlaylistToGroup("playlist", "group", 2)
        }

        val export = exportDeferred.await()
        writer.await()

        val exportedOccurrences = export.ungroupedPlaylists.count { it.name == "Playlist" } +
            export.groups.sumOf { group -> group.playlists.count { it.name == "Playlist" } }
        assertEquals(1, exportedOccurrences)
        assertEquals(
            "group",
            database.playlistDao().getPlaylistsByIds(listOf("playlist")).single().groupId,
        )
    }
}
