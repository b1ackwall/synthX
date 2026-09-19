package com.v1per.synthx.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P8-T1/R1-F07: [SongRepositoryImpl.createPlaylist] used to read [PlaylistDao.getExistingArtHues]
 * and insert the new row as two separate, unguarded statements — two concurrent creators could
 * both read the same used-hue snapshot and pick the same "unused" hue before either write landed.
 * Wrapping the read+insert in [androidx.room.withTransaction] fixes this by making Room serialize
 * the two creators' transactions rather than letting their reads interleave.
 */
@RunWith(AndroidJUnit4::class)
class CreatePlaylistArtRaceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: SynthXDatabase

    @Before fun setUp() = runBlocking {
        RestoreStartup.await()
        RestoreCoordinator.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        // A non-empty starting hue set — createPlaylist's very first call ever falls back to a
        // genuinely random hue (nothing to distance against yet), which this race isn't about.
        database.playlistDao().insertPlaylist(
            PlaylistEntity(
                id = "seed", name = "Seed", description = null, groupId = null, position = 0,
                createdAt = 1L, updatedAt = 1L, coverArtUri = null, artHue = 0, artStructureSeed = 0L,
            ),
        )
    }

    @After fun tearDown() {
        database.close()
        RestoreCoordinator.resetForTest()
    }

    private fun repositoryWith(playlistDao: PlaylistDao): SongRepositoryImpl {
        val exports = PlaylistExportManager(
            context, database, database.playlistDao(), database.songDao(),
            database.artistDao(), database.albumDao(),
        )
        return SongRepositoryImpl(
            context, database, database.songDao(), database.artistDao(), database.albumDao(),
            playlistDao, database.queueDao(), database.pendingCleanupDao(),
            FolderScanner(
                context, database, database.songDao(), database.artistDao(), database.albumDao(),
                database.songArtistDao(), exports,
            ),
            BackupManager(context), exports,
            destructiveFileCheck = { _, _ -> DestructiveFilePreflightResult.Ambiguous },
            replacementFileCheck = { _, _ -> ReplacementFilePreflightResult.Ambiguous },
        )
    }

    @Test
    fun concurrentCreatorsNeverPickTheSameHue() = runBlocking {
        // Artificially widens the window between the hue read and the eventual insert for
        // EVERY caller — without the transaction fix this reliably reproduces the collision
        // (every concurrent caller reads the same pre-insert snapshot); with the fix, Room
        // simply can't let a second creator's transaction body run until the first commits,
        // so this delay costs wall-clock time but never changes the outcome.
        val slowReadDao = object : PlaylistDao by database.playlistDao() {
            override suspend fun getExistingArtHues(): List<Int> {
                delay(40)
                return database.playlistDao().getExistingArtHues()
            }
        }
        val repository = repositoryWith(slowReadDao)

        val ids = (1..6).map { i ->
            async(Dispatchers.Default) { repository.createPlaylist("Concurrent $i", null) }
        }.awaitAll()

        val hues = database.playlistDao().getPlaylistsByIds(ids).map { it.artHue }
        assertEquals(6, hues.size)
        assertTrue("Every created playlist must have a hue assigned: $hues", hues.all { it != null })
        assertEquals(
            "Concurrent creators picked colliding hues: $hues",
            6,
            hues.toSet().size,
        )
        assertTrue("A concurrent creator reused the pre-seeded hue: $hues", 0 !in hues)
    }
}
