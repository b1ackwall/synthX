package com.v1per.synthx.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PlaylistDao.setPlaylistArt]'s idempotence guard and
 * [PlaylistDao.getPlaylistIdsMissingArt]'s deterministic ordering — both exercised directly at
 * the DAO/SQL level, independent of [com.v1per.synthx.data.playlistart.PlaylistArtBackfill]'s own
 * mutex (that primitive is what stops two passes from wastefully racing in the first place; this
 * guard is what makes any write that gets through anyway harmless rather than corrupting).
 */
@RunWith(AndroidJUnit4::class)
class PlaylistArtDaoInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private lateinit var database: SynthXDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun setPlaylistArtIsANoOpOnceAlreadyAssigned() = runBlocking {
        val dao = database.playlistDao()
        dao.insertPlaylist(
            PlaylistEntity(
                id = "p1", name = "Playlist", description = null, groupId = null, position = 0,
                createdAt = 1L, updatedAt = 1L, coverArtUri = null, artHue = null, artStructureSeed = null,
            ),
        )

        dao.setPlaylistArt("p1", hue = 12, seed = 100L)
        // A second, later write for the same row — exactly what an overlapping backfill pass
        // (or any other stray caller) could attempt. Must not re-roll what the first write
        // already decided.
        dao.setPlaylistArt("p1", hue = 200, seed = 999L)

        val row = dao.getPlaylistsByIds(listOf("p1")).single()
        assertEquals(
            "A second write to an already-assigned row must be silently rejected, not re-roll its hue",
            12,
            row.artHue,
        )
        assertEquals(
            "A second write to an already-assigned row must be silently rejected, not re-roll its seed",
            100L,
            row.artStructureSeed,
        )
    }

    @Test fun setPlaylistArtStillWritesARowThatGenuinelyHasNoArtYet() = runBlocking {
        val dao = database.playlistDao()
        dao.insertPlaylist(
            PlaylistEntity(
                id = "p1", name = "Playlist", description = null, groupId = null, position = 0,
                createdAt = 1L, updatedAt = 1L, coverArtUri = null, artHue = null, artStructureSeed = null,
            ),
        )

        dao.setPlaylistArt("p1", hue = 12, seed = 100L)

        val row = dao.getPlaylistsByIds(listOf("p1")).single()
        assertEquals(12, row.artHue)
        assertEquals(100L, row.artStructureSeed)
    }

    @Test fun getPlaylistIdsMissingArtBreaksIdenticalTimestampTiesById() = runBlocking {
        val dao = database.playlistDao()
        // A batch of rows can share the SAME created_at — this
        // reproduces that exact shape, inserted in an order that does NOT already match id sort,
        // so a test that passed by coincidence (insertion order == id order) can't hide here.
        listOf("c-playlist", "a-playlist", "b-playlist").forEach { id ->
            dao.insertPlaylist(
                PlaylistEntity(
                    id = id, name = id, description = null, groupId = null, position = 0,
                    createdAt = 5_000L, updatedAt = 5_000L, coverArtUri = null,
                    artHue = null, artStructureSeed = null,
                ),
            )
        }

        val missing = dao.getPlaylistIdsMissingArt()

        assertEquals(listOf("a-playlist", "b-playlist", "c-playlist"), missing)
    }

    @Test fun getExistingArtHuesReducesOutOfRangeValuesAndKeepsDuplicates() = runBlocking {
        val dao = database.playlistDao()
        // In-range, a repeat of it, over 359, negative, and NULL (which must be skipped). These
        // are the shapes a restored or hand-edited row can have; the read must hand back the
        // hue each one wraps to, one row per playlist, without touching what is stored.
        listOf("a" to 40, "b" to 40, "c" to 400, "d" to -10, "e" to 360, "f" to null).forEach { (id, hue) ->
            dao.insertPlaylist(
                PlaylistEntity(
                    id = id, name = id, description = null, groupId = null, position = 0,
                    createdAt = 1L, updatedAt = 1L, coverArtUri = null,
                    artHue = hue, artStructureSeed = if (hue == null) null else 1L,
                ),
            )
        }

        assertEquals(listOf(0, 40, 40, 40, 350), dao.getExistingArtHues().sorted())
        assertEquals(
            "Normalising on read must not rewrite the stored value",
            400,
            dao.getPlaylistsByIds(listOf("c")).single().artHue,
        )
    }
}
