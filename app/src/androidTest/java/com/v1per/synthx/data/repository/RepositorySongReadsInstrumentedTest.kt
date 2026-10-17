package com.v1per.synthx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongWithDetails
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Song lists read through the real repository from real Room: order, mapping, completeness. */
@RunWith(AndroidJUnit4::class)
class RepositorySongReadsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private lateinit var fx: RepositoryTestFixture
    private val repository get() = fx.repository

    @Before fun setUp() {
        fx = RepositoryTestFixture(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = fx.close()

    private fun List<Song>.titles() = map { it.title }

    @Test fun songsAreSortedByTitleIgnoringCase() = runBlocking {
        fx.song("c", title = "charlie")
        fx.song("b", title = "Bravo")
        fx.song("a", title = "alpha")
        fx.song("d", title = "Delta")
        fx.song("e", title = "beta")

        val songs = awaitEmission(repository.getSongs(), "songs") { it.isNotEmpty() }

        assertEquals(listOf("alpha", "beta", "Bravo", "charlie", "Delta"), songs.titles())
    }

    @Test fun pinnedSongsListIncludesMissingSongsFlaggedMissing() = runBlocking {
        fx.song("here", title = "A here")
        fx.song("gone", title = "B gone", missing = true)
        fx.song("also", title = "C also here")

        val songs = awaitEmission(repository.getSongs(), "songs") { it.isNotEmpty() }

        assertEquals(listOf("here", "gone", "also"), songs.map { it.id })
        assertEquals(listOf(false, true, false), songs.map { it.isMissing })
    }

    @Test fun songCarriesArtistsInPositionOrderAndItsAlbum() = runBlocking {
        // Inserted in name order, linked in another order: only song_artists.position decides.
        fx.artist("amy", "Amy")
        fx.artist("kim", "Kim")
        fx.artist("zed", "Zed")
        fx.album("album", "The Album")
        fx.song("s", title = "Song", albumId = "album", dateAdded = 7, artistIds = listOf("zed", "amy", "kim"))

        val song = awaitEmission(repository.getSongs(), "songs") { it.isNotEmpty() }.single()

        assertEquals("Zed, Amy, Kim", song.artist)
        assertEquals(listOf("zed", "amy", "kim"), song.artists.map { it.id })
        assertEquals(listOf("Zed", "Amy", "Kim"), song.artists.map { it.name })
        assertEquals("The Album", song.album)
        assertEquals("album", song.albumId)
        assertEquals("test://s", song.filePath)
        assertEquals(1_000L, song.duration)
        assertEquals(7L, song.dateAdded)
    }

    @Test fun songWithoutArtistOrAlbumShowsTheUnknownFallbacks() = runBlocking {
        fx.song("bare", title = "Bare")

        val song = awaitEmission(repository.getSongs(), "songs") { it.isNotEmpty() }.single()

        assertEquals("Unknown Artist", song.artist)
        assertTrue(song.artists.isEmpty())
        assertEquals("Unknown Album", song.album)
        assertEquals(null, song.albumId)
    }

    @Test fun songsListFollowsLaterDatabaseChanges() = runBlocking {
        fx.song("first", title = "First")
        val songs = repository.getSongs()
        awaitEmission(songs, "songs before the insert") { it.titles() == listOf("First") }

        fx.song("added", title = "Added later")

        awaitEmission(songs, "songs after the insert") { it.titles() == listOf("Added later", "First") }
        Unit
    }

    @Test fun songsListRecoversAfterOneDatabaseError() = runBlocking {
        fx.song("s", title = "Survivor")
        val calls = AtomicInteger()
        val flakyDao = object : SongDao by fx.songDao {
            override fun getAllSongsWithDetails(): Flow<List<SongWithDetails>> =
                if (calls.getAndIncrement() == 0) {
                    flow { throw IllegalStateException("simulated database error") }
                } else {
                    fx.songDao.getAllSongsWithDetails()
                }
        }

        val songs = awaitEmission(fx.repositoryWith(flakyDao).getSongs(), "songs after one error") {
            it.isNotEmpty()
        }

        assertEquals(listOf("Survivor"), songs.titles())
        assertEquals("the failed query was retried once", 2, calls.get())
    }

    @Test fun starredListHoldsOnlyStarredSongsNewestFirstMissingIncluded() = runBlocking {
        fx.song("old", dateAdded = 10)
        fx.song("filed", dateAdded = 20, starred = false)
        fx.song("missing", dateAdded = 30, missing = true)
        fx.song("new", dateAdded = 40)

        val starred = awaitEmission(repository.getStarredSongs(), "starred") { it.isNotEmpty() }

        assertEquals(listOf("new", "missing", "old"), starred.map { it.id })
    }

    @Test fun starredCountLeavesOutMissingSongs() = runBlocking {
        fx.song("one")
        fx.song("two")
        fx.song("missing", missing = true)
        fx.song("filed", starred = false)

        val count = awaitEmission(repository.getStarredSongCount(), "starred count") { true }

        assertEquals(2, count)
    }

    @Test fun dismissingASongTakesItOffStarredForGood() = runBlocking {
        fx.song("keep", dateAdded = 2)
        fx.song("dismiss", dateAdded = 1)
        val starred = repository.getStarredSongs()
        awaitEmission(starred, "starred before") { it.size == 2 }

        repository.dismissFromStarred("dismiss")

        awaitEmission(starred, "starred after the dismissal") { it.map { s -> s.id } == listOf("keep") }
        awaitEmission(repository.getStarredSongCount(), "count after the dismissal") { it == 1 }
        // Leaving every playlist does not star it again.
        fx.playlist("p", songIds = arrayOf("dismiss"))
        repository.removeSongFromPlaylist("p", "dismiss")
        assertEquals(false, fx.songDao.getSongById("dismiss")!!.isStarred)
    }

    @Test fun recentSongsAreNewestFirstWithinTheLimitMissingIncluded() = runBlocking {
        fx.song("d1", dateAdded = 1)
        fx.song("d4", dateAdded = 4, missing = true)
        fx.song("d2", dateAdded = 2)
        fx.song("d3", dateAdded = 3)

        val recent = awaitEmission(repository.getRecentSongs(3), "recent") { it.isNotEmpty() }

        assertEquals(listOf("d4", "d3", "d2"), recent.map { it.id })
    }

    @Test fun playlistSongsComeInAddedOrderNotTitleOrder() = runBlocking {
        fx.song("z", title = "Zulu")
        fx.song("a", title = "Alpha")
        fx.song("m", title = "Mike")
        fx.song("other", title = "Not in this playlist")
        fx.playlist("p", songIds = arrayOf("m", "z", "a"))
        fx.playlist("q", songIds = arrayOf("other"))

        val songs = awaitEmission(repository.getSongsForPlaylist("p"), "playlist songs") { it.isNotEmpty() }

        assertEquals(listOf("Mike", "Zulu", "Alpha"), songs.titles())
    }

    @Test fun pinnedPlaylistSongsIncludeMissingSongsInTheirPlace() = runBlocking {
        fx.artist("art", "Artist")
        fx.song("one", artistIds = listOf("art"))
        fx.song("gone", missing = true, artistIds = listOf("art"))
        fx.song("three", artistIds = listOf("art"))
        fx.playlist("p", songIds = arrayOf("one", "gone", "three"))

        val songs = awaitEmission(repository.getSongsForPlaylist("p"), "playlist songs") { it.isNotEmpty() }

        assertEquals(listOf("one", "gone", "three"), songs.map { it.id })
        assertEquals(listOf(false, true, false), songs.map { it.isMissing })
        assertEquals(listOf("Artist", "Artist", "Artist"), songs.map { it.artist })
    }

    @Test fun songsByIdsFollowTheAskedOrderDropUnknownIdsAndRepeats() = runBlocking {
        fx.song("a")
        fx.song("b")
        fx.song("c", missing = true)

        val songs = repository.getSongsByIds(listOf("c", "nope", "a", "c", "b"))

        assertEquals(listOf("c", "a", "b"), songs.map { it.id })
    }
}
