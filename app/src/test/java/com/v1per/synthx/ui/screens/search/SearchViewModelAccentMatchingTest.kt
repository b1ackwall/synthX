package com.v1per.synthx.ui.screens.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.ui.screens.search.SearchViewModel.SearchResults
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the search box treats accents and scripts: Latin accents are ignored on both the typed
 * query and the library text, every other script is compared exactly (apart from case). Each
 * case waits for the first non-empty result set, so the expected set is always non-empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelAccentMatchingTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test
    fun anUnaccentedQueryFindsAnAccentedArtist() {
        val library = SearchLibrary().apply {
            artists.value = listOf(Artist("ar-tiesto", "Tiësto"), Artist("ar-none", "Daft Punk"))
        }
        assertEquals(listOf("ar-tiesto"), search(library, "tiesto").artists.map { it.id })
        assertEquals(listOf("ar-tiesto"), search(library, "TIESTO").artists.map { it.id })
    }

    @Test
    fun anUnaccentedQueryFindsAnAccentedSongTitleAndSongArtist() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-artist", title = "Halo", artist = "Beyoncé"),
                searchSong("s-title", title = "Beyoncé Medley", artist = "Various"),
                searchSong("s-none", title = "Daylight", artist = "Kavinsky"),
            )
        }
        assertEquals(listOf("s-artist", "s-title"), search(library, "beyonce").songs.map { it.id })
    }

    @Test
    fun anAccentedQueryFindsAnUnaccentedTitleAndAnAccentedOne() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-plain", title = "Tiesto Mix", artist = "Various"),
                searchSong("s-accent", title = "Tiësto Mix", artist = "Various"),
                searchSong("s-none", title = "Daylight", artist = "Kavinsky"),
            )
        }
        assertEquals(listOf("s-plain", "s-accent"), search(library, "tiësto").songs.map { it.id })
    }

    @Test
    fun aDecomposedTagMatchesAComposedQueryAndAPlainQuery() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-nfd", title = "Tiësto Mix", artist = "Various"),
                searchSong("s-none", title = "Daylight", artist = "Kavinsky"),
            )
        }
        assertEquals(listOf("s-nfd"), search(library, "tiësto").songs.map { it.id })
        assertEquals(listOf("s-nfd"), search(library, "tiesto").songs.map { it.id })
        assertEquals(listOf("s-nfd"), search(library, "Tiësto").songs.map { it.id })
    }

    @Test
    fun anAccentedAlbumTitleAndAlbumArtistAreFoundWithoutAccents() {
        val library = SearchLibrary().apply {
            albums.value = listOf(
                Album("al-title", title = "Sigur Rós Live", artist = "Various", albumArtUri = null),
                Album("al-artist", title = "Valtari", artist = "Sigur Rós", albumArtUri = null),
                Album("al-none", title = "Discovery", artist = "Daft Punk", albumArtUri = null),
            )
        }
        assertEquals(listOf("al-title", "al-artist"), search(library, "sigur ros").albums.map { it.id })
    }

    @Test
    fun anAccentedPlaylistNameIsFoundWithoutAccents() {
        val library = SearchLibrary().apply {
            playlists.value = listOf(
                Playlist("p-accent", name = "Café Mix", groupId = null, albumArtUri = null),
                Playlist("p-none", name = "Gym", groupId = null, albumArtUri = null),
            )
        }
        assertEquals(listOf("p-accent"), search(library, "cafe").playlists.map { it.id })
    }

    @Test
    fun lettersThatDoNotDecomposeMatchTheirPlainSpelling() {
        val titles = mapOf(
            "Bjørk" to "bjork",
            "Łódź" to "lodz",
            "Đorđe" to "dorde",
            "Æon" to "aeon",
            "Cœur" to "coeur",
            "Straße" to "strasse",
        )
        for ((title, query) in titles) {
            val library = SearchLibrary().apply {
                songs.value = listOf(
                    searchSong("s-hit", title = title, artist = "Various"),
                    searchSong("s-none", title = "Daylight", artist = "Kavinsky"),
                )
            }
            assertEquals(listOf("s-hit"), search(library, query).songs.map { it.id }, "$query should find $title")
        }
    }

    @Test
    fun lettersThatDoNotDecomposeMatchWhenTypedWithTheirOwnSpelling() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-plain", title = "Bjork Live", artist = "Various"),
                searchSong("s-slash", title = "Bjørk Live", artist = "Various"),
                searchSong("s-none", title = "Daylight", artist = "Kavinsky"),
            )
        }
        assertEquals(listOf("s-plain", "s-slash"), search(library, "bjørk").songs.map { it.id })
    }

    @Test
    fun accentedResultsKeepTheLibraryOrder() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-b", title = "Tiesto B", artist = "Various"),
                searchSong("s-a", title = "Tiësto A", artist = "Various"),
                searchSong("s-c", title = "TIËSTO C", artist = "Various"),
            )
        }
        assertEquals(listOf("s-b", "s-a", "s-c"), search(library, "tiesto").songs.map { it.id })
    }

    @Test
    fun resultsFollowTheLibraryWhileAnAccentedQueryIsActive() = withViewModel(SearchLibrary()) { vm, library ->
        vm.onQueryChange("beyonce")
        library.songs.value = listOf(searchSong("s-first", title = "Halo", artist = "Beyoncé"))
        awaitResults(vm) { it.songs.size == 1 }

        library.songs.value = library.songs.value + searchSong("s-second", title = "Beyoncé Medley", artist = "X")
        val results = awaitResults(vm) { it.songs.size == 2 }
        assertEquals(listOf("s-first", "s-second"), results.songs.map { it.id })
    }

    @Test
    fun cyrillicMatchingIgnoresCaseButKeepsShortIAndYoDistinct() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-privet", title = "Привет", artist = "Various"),
                searchSong("s-yod", title = "Йод", artist = "Various"),
                searchSong("s-iod", title = "Иод", artist = "Various"),
                searchSong("s-yolka", title = "Ёлка", artist = "Various"),
                searchSong("s-elka", title = "Елка", artist = "Various"),
            )
        }
        assertEquals(listOf("s-privet"), search(library, "ПРИВЕТ").songs.map { it.id })
        assertEquals(listOf("s-yod"), search(library, "йод").songs.map { it.id })
        assertEquals(listOf("s-iod"), search(library, "иод").songs.map { it.id })
        assertEquals(listOf("s-yolka"), search(library, "ёлка").songs.map { it.id })
        assertEquals(listOf("s-elka"), search(library, "елка").songs.map { it.id })
    }

    @Test
    fun kanaVoicingMarksAndKanaKindsStayDistinct() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-ka", title = "か", artist = "Various"),
                searchSong("s-ga", title = "が", artist = "Various"),
                searchSong("s-kata-ka", title = "カ", artist = "Various"),
                searchSong("s-kata-ga", title = "ガ", artist = "Various"),
            )
        }
        assertEquals(listOf("s-ka"), search(library, "か").songs.map { it.id })
        assertEquals(listOf("s-ga"), search(library, "が").songs.map { it.id })
        assertEquals(listOf("s-kata-ka"), search(library, "カ").songs.map { it.id })
        assertEquals(listOf("s-kata-ga"), search(library, "ガ").songs.map { it.id })
    }

    @Test
    fun hangulSyllablesAreNotMatchedByTheirLeadingJamoSyllable() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-han", title = "한글", artist = "Various"),
                searchSong("s-ha", title = "하늘", artist = "Various"),
            )
        }
        assertEquals(listOf("s-han"), search(library, "한글").songs.map { it.id })
        assertEquals(listOf("s-ha"), search(library, "하").songs.map { it.id })
    }

    @Test
    fun devanagariTextIsComparedExactly() {
        val library = SearchLibrary().apply {
            songs.value = listOf(
                searchSong("s-namaste", title = "नमस्ते", artist = "Various"),
                searchSong("s-hindi", title = "हिन्दी", artist = "Various"),
            )
        }
        assertEquals(listOf("s-namaste"), search(library, "नमस्ते").songs.map { it.id })
        assertEquals(listOf("s-hindi"), search(library, "हिन्दी").songs.map { it.id })
    }

    private fun search(library: SearchLibrary, query: String): SearchResults {
        var found = SearchResults()
        withViewModel(library) { vm, _ ->
            vm.onQueryChange(query)
            found = awaitResults(vm) { !it.isEmpty }
        }
        return found
    }

    /** The first non-empty set, or whatever the screen shows after 1.5 s so a miss fails on its assertion. */
    private fun awaitResults(vm: SearchViewModel, until: (SearchResults) -> Boolean): SearchResults = runBlocking {
        withTimeoutOrNull(1_500) { vm.results.first(until) } ?: vm.results.value
    }

    private fun withViewModel(library: SearchLibrary, block: (SearchViewModel, SearchLibrary) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = SearchViewModel(library.repository(), Application())
            try {
                block(viewModel, library)
            } finally {
                runBlocking { viewModel.viewModelScope.coroutineContext.job.cancelAndJoin() }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
