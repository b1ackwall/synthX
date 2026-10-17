package com.v1per.synthx.data.repository

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

/** Album and artist lists, and their song lists, read through the real repository from real Room. */
@RunWith(AndroidJUnit4::class)
class RepositoryAlbumArtistReadsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private lateinit var fx: RepositoryTestFixture
    private val repository get() = fx.repository

    @Before fun setUp() {
        fx = RepositoryTestFixture(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = fx.close()

    @Test fun albumsAreSortedByTitleIgnoringCase() = runBlocking {
        fx.album("g", "gamma")
        fx.album("a", "Alpha")
        fx.album("b", "beta")
        fx.album("c", "Charlie")

        val albums = awaitEmission(repository.getAlbums(), "albums") { it.isNotEmpty() }

        assertEquals(listOf("Alpha", "beta", "Charlie", "gamma"), albums.map { it.title })
    }

    @Test fun albumArtistIsTheMainArtistOfItsLowestTrack() = runBlocking {
        fx.artist("first", "First Artist")
        fx.artist("second", "Second Artist")
        fx.artist("guest", "Guest")
        fx.album("album", "Album")
        fx.album("bare", "Bare")
        fx.song("t2", albumId = "album", trackNumber = 2, artistIds = listOf("second"))
        fx.song("untracked", albumId = "album", artistIds = listOf("guest"))
        fx.song("t1", albumId = "album", trackNumber = 1, artistIds = listOf("first", "guest"))
        fx.song("nobody", albumId = "bare")

        val albums = awaitEmission(repository.getAlbums(), "albums") { it.isNotEmpty() }

        assertEquals(listOf("Album" to "First Artist", "Bare" to "Unknown Artist"), albums.map { it.title to it.artist })
    }

    @Test fun pinnedAlbumsWhoseSongsAreAllMissingStayListedAndFlagged() = runBlocking {
        fx.artist("art", "Artist")
        fx.album("all-gone", "All gone")
        fx.album("half", "Half")
        fx.album("empty", "Empty")
        fx.song("g1", albumId = "all-gone", trackNumber = 1, missing = true, artistIds = listOf("art"))
        fx.song("g2", albumId = "all-gone", trackNumber = 2, missing = true)
        fx.song("h1", albumId = "half", missing = true)
        fx.song("h2", albumId = "half")

        val albums = awaitEmission(repository.getAlbums(), "albums") { it.isNotEmpty() }

        assertEquals(
            listOf(Triple("All gone", true, "Artist"), Triple("Empty", true, "Unknown Artist"), Triple("Half", false, "Unknown Artist")),
            albums.map { Triple(it.title, it.allSongsMissing, it.artist) },
        )
    }

    @Test fun albumSongsRunByTrackThenUntrackedByTitleMissingIncluded() = runBlocking {
        fx.album("album", "Album")
        fx.album("other", "Other")
        fx.song("u-b", title = "beta", albumId = "album")
        fx.song("t3", title = "Three", albumId = "album", trackNumber = 3)
        fx.song("u-a", title = "Alpha", albumId = "album")
        fx.song("t1", title = "One", albumId = "album", trackNumber = 1, missing = true)
        fx.song("t2", title = "Two", albumId = "album", trackNumber = 2)
        fx.song("x", title = "Elsewhere", albumId = "other", trackNumber = 1)

        val songs = awaitEmission(repository.getSongsForAlbum("album"), "album songs") { it.isNotEmpty() }

        assertEquals(listOf("t1", "t2", "t3", "u-a", "u-b"), songs.map { it.id })
        assertEquals(true, songs.first().isMissing)
    }

    @Test fun artistsAreSortedByNameIgnoringCase() = runBlocking {
        fx.artist("z", "zappa")
        fx.artist("a", "ABBA")
        fx.artist("b", "beck")
        fx.artist("c", "Cher")

        val artists = awaitEmission(repository.getArtists(), "artists") { it.isNotEmpty() }

        assertEquals(listOf("ABBA", "beck", "Cher", "zappa"), artists.map { it.name })
    }

    @Test fun pinnedArtistsWhoseSongsAreAllMissingStayListedAndFlagged() = runBlocking {
        fx.artist("gone", "Gone")
        fx.artist("live", "Live")
        fx.artist("lonely", "Lonely")
        fx.song("g", missing = true, artistIds = listOf("gone"))
        fx.song("l1", missing = true, artistIds = listOf("live"))
        fx.song("l2", artistIds = listOf("gone", "live"))
        fx.song("g2", missing = true, artistIds = listOf("gone"))

        val artists = awaitEmission(repository.getArtists(), "artists") { it.isNotEmpty() }

        assertEquals(
            listOf("Gone" to false, "Live" to false, "Lonely" to true),
            artists.map { it.name to it.allSongsMissing },
        )
        fx.song("only-gone", missing = true, artistIds = listOf("lonely"))
        fx.songDao.markMissing("l2", 5)
        awaitEmission(repository.getArtists(), "artists after l2 went missing") { list ->
            list.map { it.name to it.allSongsMissing } == listOf("Gone" to true, "Live" to true, "Lonely" to true)
        }
        Unit
    }

    @Test fun artistSongsIncludeFeaturedSongsInTitleOrderMissingIncluded() = runBlocking {
        fx.artist("main", "Main")
        fx.artist("guest", "Guest")
        fx.song("lead", title = "lead single", artistIds = listOf("guest"))
        fx.song("feature", title = "featured", artistIds = listOf("main", "guest"))
        fx.song("gone", title = "Gone song", missing = true, artistIds = listOf("guest"))
        fx.song("solo", title = "Solo", artistIds = listOf("main"))

        val songs = awaitEmission(repository.getSongsForArtist("guest"), "artist songs") { it.isNotEmpty() }

        assertEquals(listOf("feature", "gone", "lead"), songs.map { it.id })
        assertEquals("Main, Guest", songs.first().artist)
    }
}
