package com.v1per.synthx.ui.screens.replacesong

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the Replace Song pickers list for a query: songs whose title or artist contains it,
 * trimmed, any case, Latin accents ignored on both sides, other scripts compared exactly.
 * A blank query lists the whole library.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceSongViewModelMatchingTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val library = listOf(
        song("s-tiesto", title = "Red Lights", artist = "Tiësto"),
        song("s-beyonce", title = "Beyoncé Medley", artist = "Various"),
        song("s-plain", title = "Tiesto Mix", artist = "Various"),
        song("s-yod", title = "Йод", artist = "Various"),
        song("s-iod", title = "Иод", artist = "Various"),
        song("s-ga", title = "が", artist = "Various"),
        song("s-ka", title = "か", artist = "Various"),
        song("s-night", title = "Midnight City", artist = "M83"),
    )

    @Test
    fun aBlankQueryListsTheWholeLibrary() {
        assertEquals(library.map { it.id }, picked(""))
    }

    @Test
    fun aQueryMatchesTitleOrArtistIgnoringCaseAndSurroundingSpaces() {
        assertEquals(listOf("s-night"), picked("  NIGHT "))
        assertEquals(listOf("s-night"), picked("m83"))
    }

    @Test
    fun anUnaccentedQueryFindsAccentedTitlesAndArtistsInLibraryOrder() {
        assertEquals(listOf("s-tiesto", "s-plain"), picked("tiesto"))
        assertEquals(listOf("s-beyonce"), picked("  BEYONCE "))
    }

    @Test
    fun anAccentedQueryFindsAccentedAndUnaccentedText() {
        assertEquals(listOf("s-tiesto", "s-plain"), picked("tiësto"))
        assertEquals(listOf("s-beyonce"), picked("beyoncé"))
    }

    @Test
    fun cyrillicAndKanaAreComparedExactly() {
        assertEquals(listOf("s-yod"), picked("йод"))
        assertEquals(listOf("s-iod"), picked("иод"))
        assertEquals(listOf("s-ga"), picked("が"))
        assertEquals(listOf("s-ka"), picked("か"))
    }

    private fun picked(query: String): List<String> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val viewModel = ReplaceSongViewModel(
            repository = repository(),
            appContext = Application(),
            stringResource = { id -> error("Unexpected string resource: $id") },
        )
        try {
            viewModel.onQueryChange(query)
            return runBlocking {
                withTimeoutOrNull(1_500) { viewModel.results.first { it.isNotEmpty() && matchesQuery(it, query) } }
                    ?: viewModel.results.value
            }.map { it.id }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /** The whole library is the answer for a blank query, otherwise any non-empty set is already filtered. */
    private fun matchesQuery(songs: List<Song>, query: String): Boolean =
        (query.isBlank() && songs.size == library.size) || (query.isNotBlank() && songs.size < library.size)

    private fun repository(): SongRepository {
        val songs = MutableStateFlow(library)
        return Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSongs" -> songs
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository
    }

    private fun song(id: String, title: String, artist: String) = Song(id, title, artist, "Album", 1L, "test://$id")
}
