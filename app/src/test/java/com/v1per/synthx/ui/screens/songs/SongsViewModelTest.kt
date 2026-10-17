package com.v1per.synthx.ui.screens.songs

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.RecordingPlaybackEngine
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The Songs list: Loading, Empty and Success, the four sort orders, and what a tap plays. */
@OptIn(ExperimentalCoroutinesApi::class)
class SongsViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    // Each of the four orders puts these three in a different sequence, and TITLE differs from a
    // case-sensitive sort ("Banana" would sort before "apple").
    private val library = listOf(
        listSong("x", title = "cherry", artist = "Bjork", album = "Amnesiac", dateAdded = 200L),
        listSong("y", title = "apple", artist = "Coldplay", album = "Homogenic", dateAdded = 300L),
        listSong("z", title = "Banana", artist = "aphex Twin", album = "Blue Lines", dateAdded = 100L),
    )

    @Test
    fun theListIsLoadingUntilTheRepositoryAnswers() =
        withViewModel(flow { awaitCancellation() }) { vm, _ ->
            assertIs<UiState.Loading>(vm.uiState.value)
        }

    @Test
    fun anEmptyLibraryIsEmpty() = withViewModel(flowOf(emptyList())) { vm, _ ->
        assertIs<UiState.Empty>(awaitLoaded(vm))
    }

    @Test
    fun theDefaultOrderIsRecentlyAddedNewestFirst() = withViewModel(flowOf(library)) { vm, _ ->
        assertEquals(SortOrder.RECENTLY_ADDED, vm.sortOrder.value)
        assertEquals(listOf("y", "x", "z"), ids(awaitLoaded(vm)))
    }

    @Test
    fun titleOrderIgnoresCase() = withViewModel(flowOf(library)) { vm, _ ->
        vm.setSortOrder(SortOrder.TITLE)
        assertEquals(listOf("y", "z", "x"), awaitOrder(vm, listOf("y", "z", "x")))
    }

    @Test
    fun artistOrderSortsByArtistIgnoringCase() = withViewModel(flowOf(library)) { vm, _ ->
        vm.setSortOrder(SortOrder.ARTIST)
        assertEquals(listOf("z", "x", "y"), awaitOrder(vm, listOf("z", "x", "y")))
    }

    @Test
    fun albumOrderSortsByAlbum() = withViewModel(flowOf(library)) { vm, _ ->
        vm.setSortOrder(SortOrder.ALBUM)
        assertEquals(listOf("x", "z", "y"), awaitOrder(vm, listOf("x", "z", "y")))
    }

    @Test
    fun theListFollowsTheRepositoryLiveAndCanBecomeEmpty() {
        val songs = MutableStateFlow(library)
        withViewModel(songs) { vm, _ ->
            assertEquals(listOf("y", "x", "z"), ids(awaitLoaded(vm)))
            songs.value = library.take(1)
            awaitOrder(vm, listOf("x"))
            songs.value = emptyList()
            runBlocking { withTimeout(2_000) { vm.uiState.first { it is UiState.Empty } } }
        }
    }

    @Test
    fun aTapPlaysTheWholeListAsSortedFromTheTappedSong() =
        withViewModel(flowOf(library)) { vm, engine ->
            vm.setSortOrder(SortOrder.TITLE)
            awaitOrder(vm, listOf("y", "z", "x"))

            vm.onSongTap(library.first { it.id == "z" })

            assertEquals(
                listOf(RecordingPlaybackEngine.PlayCall(listOf("y", "z", "x"), 1, null)),
                engine.playQueueCalls,
            )
        }

    private fun ids(state: UiState<ImmutableList<Song>>): List<String> =
        assertIs<UiState.Success<ImmutableList<Song>>>(state).data.map { it.id }

    private fun awaitLoaded(vm: SongsViewModel): UiState<ImmutableList<Song>> =
        runBlocking { withTimeout(2_000) { vm.uiState.first { it !is UiState.Loading } } }

    // Waits for the expected order and returns what was last shown, so a wrong order fails by
    // timing out with the last order in the message.
    private fun awaitOrder(vm: SongsViewModel, expected: List<String>): List<String> {
        try {
            runBlocking {
                withTimeout(2_000) {
                    vm.uiState.first { (it as? UiState.Success)?.data?.map { s -> s.id } == expected }
                }
            }
        } catch (e: Exception) {
            throw AssertionError("expected order $expected, last shown ${vm.uiState.value}", e)
        }
        return ids(vm.uiState.value)
    }

    private fun withViewModel(
        songs: Flow<List<Song>>,
        block: (SongsViewModel, RecordingPlaybackEngine) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val engine = RecordingPlaybackEngine()
            val playback = PlaybackViewModel(PlaybackController(engine))
            val viewModel = SongsViewModel(songsRepository(songs), playback, Application())
            try {
                block(viewModel, engine)
            } finally {
                viewModel.viewModelScope.cancel()
                playback.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}

internal fun songsRepository(songs: Flow<List<Song>>): SongRepository =
    Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSongs" -> songs
            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository

internal fun listSong(id: String, title: String, artist: String, album: String, dateAdded: Long) = Song(
    id = id, title = title, artist = artist, album = album, duration = 1_000L,
    filePath = "/music/$id.mp3", dateAdded = dateAdded,
)
