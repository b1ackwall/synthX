package com.v1per.synthx.playback

import android.content.Context
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.runBlocking

/**
 * Seeds matching `songs` rows into the *real* on-device `.debug` database.
 *
 * Needed because [ExoPlayerEngine]'s reconciliation (`reconcileWithDatabase`, and the revision
 * check after a queue load) reads the live database through the `SongRepositoryImpl.create`
 * singleton — the stubbed [QueuePersistence] the playback tests use is not on that path. A queue
 * song id that is absent from the real table is, to reconciliation, a *deleted* song and is
 * stripped from the queue; so any test that reaches the reconcile path must seed the ids it
 * expects to survive.
 *
 * Only `com.v1per.synthx.debug` data is touched. Every id this seed wrote is removed by [cleanUp]
 * (cascading its queue/playlist rows), so a test run leaves the library as it found it. Requires
 * the startup gate to be ready — call after [PlaybackServiceTestBase]'s setUp.
 */
internal class RealDbSongSeed(private val context: Context) {

    private val seededIds = mutableSetOf<String>()

    private val songDao by lazy {
        RestoreStartup.await()
        SynthXDatabase.getInstance(context).songDao()
    }

    /** Inserts (or replaces) a row mirroring [song]'s id, title and `filePath`. */
    fun seed(song: Song, filePath: String = song.filePath) = runBlocking {
        songDao.upsertSong(
            SongEntity(
                id = song.id,
                title = song.title,
                albumId = null,
                duration = song.duration,
                filePath = filePath,
                albumArtUri = null,
                year = null,
                dateAdded = 1L,
            ),
        )
        seededIds += song.id
    }

    /** Removes the row, as a real deletion's committed transaction would. */
    fun delete(id: String) = runBlocking {
        songDao.deleteSongById(id)
        seededIds -= id
    }

    /** The domain [Song] the engine's reconciliation would build from the row for [id], if any. */
    fun read(id: String): Song? = runBlocking {
        SongRepositoryImpl.create(context).getSongsByIdsForReconciliation(listOf(id)).firstOrNull()
    }

    fun cleanUp() = runBlocking {
        seededIds.toList().forEach { songDao.deleteSongById(it) }
        seededIds.clear()
    }
}
