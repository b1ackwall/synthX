package com.v1per.synthx.ui.screens.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.screens.search.SearchViewModel.SearchResults
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the search box matches: songs by title or artist, albums by title or artist, artists and
 * playlists by name; trimmed, any case; a blank query shows nothing. Results follow the
 * repository live. Each wait has a 2 s limit, well past the 300 ms debounce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelMatchingTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private val library = SearchLibrary()

    @Test
    fun aQueryFindsSongsByTitleOrArtistAlbumsByTitleOrArtistAndArtistsAndPlaylistsByName() =
        withViewModel { vm ->
            vm.onQueryChange("night")
            val results = awaitResults(vm) { !it.isEmpty }

            assertEquals(listOf("s-title", "s-artist"), results.songs.map { it.id })
            assertEquals(listOf("al-title", "al-artist"), results.albums.map { it.id })
            assertEquals(listOf("ar-night"), results.artists.map { it.id })
            assertEquals(listOf("p-night"), results.playlists.map { it.id })
        }

    @Test
    fun matchingIgnoresCase() = withViewModel { vm ->
        vm.onQueryChange("NIGHT")
        val results = awaitResults(vm) { !it.isEmpty }
        assertEquals(listOf("s-title", "s-artist"), results.songs.map { it.id })
        assertEquals(listOf("p-night"), results.playlists.map { it.id })
    }

    @Test
    fun spacesAroundTheQueryAreIgnored() = withViewModel { vm ->
        vm.onQueryChange("  night  ")
        val results = awaitResults(vm) { !it.isEmpty }
        assertEquals(listOf("s-title", "s-artist"), results.songs.map { it.id })
    }

    @Test
    fun aSongFoundOnlyByItsArtistIsListed() = withViewModel { vm ->
        vm.onQueryChange("owl")
        val results = awaitResults(vm) { !it.isEmpty }
        assertEquals(listOf("s-artist"), results.songs.map { it.id }, "'Owl City' is the artist, not the title")
        assertEquals(listOf("al-artist"), results.albums.map { it.id })
    }

    @Test
    fun aBlankQueryShowsNothingEvenAfterAMatchingOne() = withViewModel { vm ->
        vm.onQueryChange("night")
        awaitResults(vm) { !it.isEmpty }

        vm.onQueryChange("   ")
        val results = awaitResults(vm) { it.isEmpty }
        assertEquals(SearchResults(), results)
    }

    @Test
    fun aQueryThatMatchesNothingGivesEmptyResults() = withViewModel { vm ->
        vm.onQueryChange("night")
        awaitResults(vm) { !it.isEmpty }

        vm.onQueryChange("zzz-no-match")
        assertTrue(awaitResults(vm) { it.isEmpty }.isEmpty)
    }

    @Test
    fun resultsFollowTheLibraryWhenItChanges() = withViewModel { vm ->
        vm.onQueryChange("night")
        awaitResults(vm) { it.songs.size == 2 }

        library.songs.value = library.songs.value + searchSong("s-new", title = "Night Drive", artist = "Kavinsky")
        val results = awaitResults(vm) { it.songs.size == 3 }
        assertEquals(listOf("s-title", "s-artist", "s-new"), results.songs.map { it.id })
    }

    private fun awaitResults(vm: SearchViewModel, until: (SearchResults) -> Boolean): SearchResults =
        runBlocking { withTimeout(2_000) { vm.results.first(until) } }

    private fun withViewModel(block: (SearchViewModel) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = SearchViewModel(library.repository(), Application())
            try {
                block(viewModel)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}

/** A small library where "night" hits every kind of result by a different field. */
internal class SearchLibrary {
    val songs = MutableStateFlow(
        listOf(
            searchSong("s-title", title = "Midnight City", artist = "M83"),
            searchSong("s-artist", title = "Fireflies", artist = "Owl City Nightly"),
            searchSong("s-none", title = "Daylight", artist = "Kavinsky"),
        )
    )
    val artists = MutableStateFlow(listOf(Artist("ar-night", "Nightwish"), Artist("ar-none", "Daft Punk")))
    val albums = MutableStateFlow(
        listOf(
            Album("al-title", title = "Night Visions", artist = "Imagine Dragons", albumArtUri = null),
            Album("al-artist", title = "Ocean Eyes", artist = "Owl City Nightly", albumArtUri = null),
            Album("al-none", title = "Discovery", artist = "Daft Punk", albumArtUri = null),
        )
    )
    val playlists = MutableStateFlow(
        listOf(
            Playlist("p-night", name = "Late Night", groupId = null, albumArtUri = null),
            Playlist("p-none", name = "Gym", groupId = null, albumArtUri = null),
        )
    )

    fun repository(songsFlow: Flow<List<Song>> = songs): SongRepository =
        Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSongs" -> songsFlow
                "getArtists" -> artists
                "getAlbums" -> albums
                "getAllPlaylists" -> playlists
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository
}

internal fun searchSong(id: String, title: String, artist: String) = Song(
    id = id, title = title, artist = artist, album = "Album", duration = 1_000L, filePath = "/music/$id.mp3",
)
