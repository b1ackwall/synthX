package com.v1per.synthx.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.screens.search.SearchScreen
import com.v1per.synthx.ui.screens.search.SearchViewModel
import com.v1per.synthx.ui.screens.songs.SongsScreen
import com.v1per.synthx.ui.screens.songs.SongsViewModel
import com.v1per.synthx.ui.screens.songs.SortOrder
import com.v1per.synthx.ui.screens.starred.StarredScreen
import com.v1per.synthx.ui.screens.starred.StarredViewModel
import com.v1per.synthx.ui.testing.FailOnceFlow
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.flowOnlyRepository
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Songs, Starred and Search screens over a repository whose list fails once. Checks the
 * Error state the user sees (the JVM cannot: Context.getString is a stub there), and pins bug #230:
 * after one error the screen never asks the repository again, so newer data never shows.
 */
@RunWith(AndroidJUnit4::class)
class ListScreenErrorStateInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewModels = mutableListOf<ViewModel>()

    private val first = listOf(song("e1", "Before Error One", 2L), song("e2", "Before Error Two", 1L))
    private val later = first + song("e3", "After Retry Song", 3L)

    @After fun tearDown() {
        composeRule.runOnUiThread { viewModels.forEach { it.viewModelScope.cancel() } }
    }

    private fun song(id: String, title: String, added: Long) = Song(
        id = id,
        title = title,
        artist = "Error Artist",
        album = "Error Album",
        duration = 1_000L,
        filePath = "file:///$id.mp3",
        dateAdded = added,
    )

    private fun <T : ViewModel> onUi(build: () -> T): T {
        lateinit var built: T
        composeRule.runOnUiThread { built = build() }
        viewModels += built
        return built
    }

    private fun playback() = onUi { PlaybackViewModel(PlaybackController(FakePlaybackEngine())) }

    private fun show(content: @Composable () -> Unit) = composeRule.setContent { SynthXTheme { content() } }

    private fun count(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun awaitText(text: String) = composeRule.waitUntil(WAIT_MS) { count(text) > 0 }

    /** Waits long enough for a re-subscription to show, since the claim is that nothing happens. */
    private fun settleQuietly(ms: Long) {
        Thread.sleep(ms)
        composeRule.waitForIdle()
    }

    private fun startSongs(songs: FailOnceFlow<List<Song>>): SongsViewModel {
        val playback = playback()
        val viewModel = onUi { SongsViewModel(flowOnlyRepository(mapOf("getSongs" to songs.flow)), playback, context) }
        show {
            SongsScreen(
                topBar = { onSortClick -> Button(onClick = onSortClick) { Text(SORT_BUTTON) } },
                playbackViewModel = playback,
                // The screen's own setter, exactly what the shell's sort sheet calls on a pick.
                onShowSortSheet = { _, onOrderSelected -> onOrderSelected(SortOrder.TITLE) },
                viewModel = viewModel,
            )
        }
        awaitText(first.first().title)
        return viewModel
    }

    private fun startStarred(starred: FailOnceFlow<List<Song>>) {
        val playback = playback()
        val viewModel = onUi {
            StarredViewModel(flowOnlyRepository(mapOf("getStarredSongs" to starred.flow)), playback, context)
        }
        show { StarredScreen(topBar = {}, playbackViewModel = playback, viewModel = viewModel) }
        awaitText(first.first().title)
    }

    private fun startSearch(songs: FailOnceFlow<List<Song>>) {
        val playback = playback()
        val repository = flowOnlyRepository(
            mapOf(
                "getSongs" to songs.flow,
                "getArtists" to flowOf(emptyList<Any>()),
                "getAlbums" to flowOf(emptyList<Any>()),
                "getAllPlaylists" to flowOf(emptyList<Any>()),
            ),
        )
        val viewModel = onUi { SearchViewModel(repository, context) }
        show {
            SearchScreen(
                topBar = {},
                onNavigateToArtistDetail = { _, _ -> },
                onNavigateToAlbumDetail = { _, _ -> },
                onNavigateToPlaylistDetail = { _, _ -> },
                playbackViewModel = playback,
                searchReselectEvents = MutableSharedFlow(),
                viewModel = viewModel,
            )
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("Error")
        awaitText(first.first().title)
    }

    @Test fun songsScreenShowsItsErrorMessageInPlaceOfTheList() {
        val songs = FailOnceFlow(first, later)
        startSongs(songs)

        songs.fail()

        awaitText(context.getString(R.string.error_load_songs))
        assertEquals("the failed list is no longer shown", 0, count(first.first().title))
    }

    @Test fun pinnedAfterOneErrorSongsNeverAsksAgainEvenForANewSortOrder() {
        val songs = FailOnceFlow(first, later)
        val viewModel = startSongs(songs)
        songs.fail()
        awaitText(context.getString(R.string.error_load_songs))

        composeRule.onNodeWithText(SORT_BUTTON).performClick()
        composeRule.waitUntil(WAIT_MS) { viewModel.sortOrder.value == SortOrder.TITLE }
        settleQuietly(QUIET_MS)

        assertEquals("the songs flow was never subscribed again", 1, songs.subscriptions)
        assertEquals("the screen stays on Error", 1, count(context.getString(R.string.error_load_songs)))
        assertEquals("newer data never shows", 0, count(later.last().title))
    }

    @Test fun starredScreenShowsItsErrorMessageInPlaceOfTheList() {
        val starred = FailOnceFlow(first, later)
        startStarred(starred)

        starred.fail()

        awaitText(context.getString(R.string.error_load_starred))
        assertEquals("the failed list is no longer shown", 0, count(first.first().title))
    }

    @Test fun pinnedAfterOneErrorStarredNeverAsksAgain() {
        val starred = FailOnceFlow(first, later)
        startStarred(starred)
        starred.fail()
        awaitText(context.getString(R.string.error_load_starred))

        settleQuietly(QUIET_MS)

        assertEquals("the starred flow was never subscribed again", 1, starred.subscriptions)
        assertEquals("the screen stays on Error", 1, count(context.getString(R.string.error_load_starred)))
        assertEquals("newer data never shows", 0, count(later.last().title))
    }

    @Test fun searchScreenShowsItsErrorMessageAboveTheLastResults() {
        val songs = FailOnceFlow(first, later)
        startSearch(songs)

        songs.fail()

        awaitText(context.getString(R.string.error_search))
        assertEquals("the last results stay listed under the message", 1, count(first.first().title))
    }

    @Test fun pinnedAfterOneErrorSearchNeverAsksAgainForANewQuery() {
        val songs = FailOnceFlow(first, later)
        startSearch(songs)
        songs.fail()
        awaitText(context.getString(R.string.error_search))

        composeRule.onNode(hasSetTextAction()).performTextReplacement("After")
        // Four 300 ms debounce windows.
        settleQuietly(SEARCH_QUIET_MS)

        assertEquals("the songs flow was never subscribed again", 1, songs.subscriptions)
        assertEquals("the message stays", 1, count(context.getString(R.string.error_search)))
        assertEquals("a song matching the new query never shows", 0, count(later.last().title))
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val QUIET_MS = 700L
        const val SEARCH_QUIET_MS = 1_200L
        const val SORT_BUTTON = "test sort"
    }
}
