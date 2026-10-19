package com.v1per.synthx.ui.screens.starred

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.FailOnceFlow
import com.v1per.synthx.ui.testing.RecordingPlaybackEngine
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.withUncaughtExceptionsCaptured
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
import kotlin.test.assertTrue

/**
 * The Starred list: Loading, Empty, Success in the repository's own order (no re-sorting), what a
 * tap plays, and the pinned stop-after-one-error behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StarredViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    // Deliberately not in title, artist or date order: the list must keep it as given.
    private val starred =
        listOf(starredSong("m", dateAdded = 1L), starredSong("c", dateAdded = 3L), starredSong("t", dateAdded = 2L))

    @Test
    fun theListIsLoadingUntilTheRepositoryAnswers() = withViewModel(flow { awaitCancellation() }) { vm, _ ->
        assertIs<UiState.Loading>(vm.uiState.value)
    }

    @Test
    fun noStarredSongsIsEmpty() = withViewModel(flowOf(emptyList())) { vm, _ ->
        assertIs<UiState.Empty>(awaitLoaded(vm))
    }

    @Test
    fun songsKeepTheRepositorysOrder() = withViewModel(flowOf(starred)) { vm, _ ->
        assertEquals(listOf("m", "c", "t"), ids(awaitLoaded(vm)))
    }

    @Test
    fun theListFollowsTheRepositoryLive() {
        val songs = MutableStateFlow(starred)
        withViewModel(songs) { vm, _ ->
            assertEquals(listOf("m", "c", "t"), ids(awaitLoaded(vm)))
            songs.value = starred.drop(1)
            runBlocking {
                withTimeout(2_000) {
                    vm.uiState.first { (it as? UiState.Success)?.data?.map { s -> s.id } == listOf("c", "t") }
                }
            }
        }
    }

    @Test
    fun aTapPlaysTheWholeListFromTheTappedSong() = withViewModel(flowOf(starred)) { vm, engine ->
        awaitLoaded(vm)
        vm.onSongTap(starred[2])
        assertEquals(listOf(RecordingPlaybackEngine.PlayCall(listOf("m", "c", "t"), 2, null)), engine.playQueueCalls)
    }

    /** PINNED (bug "Search, Songs and Starred stop updating after one error"); see the Songs pin. */
    @Test
    fun pinnedAfterOneStarredFlowErrorTheListStopsUpdating() = withUncaughtExceptionsCaptured {
        val songs = FailOnceFlow(first = starred, afterRetry = starred + starredSong("new", dateAdded = 9L))
        withViewModel(songs.flow) { vm, _ ->
            val before = awaitLoaded(vm)
            songs.fail()
            Thread.sleep(500)

            assertEquals(1, songs.subscriptions, "the starred flow was never subscribed again")
            val after = vm.uiState.value
            assertTrue(
                after is UiState.Error || after == before,
                "expected Error (device) or the frozen pre-error list (JVM), got $after",
            )
        }
    }

    private fun ids(state: UiState<ImmutableList<Song>>): List<String> =
        assertIs<UiState.Success<ImmutableList<Song>>>(state).data.map { it.id }

    private fun awaitLoaded(vm: StarredViewModel): UiState<ImmutableList<Song>> =
        runBlocking { withTimeout(2_000) { vm.uiState.first { it !is UiState.Loading } } }

    private fun withViewModel(songs: Flow<List<Song>>, block: (StarredViewModel, RecordingPlaybackEngine) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val engine = RecordingPlaybackEngine()
            val playback = PlaybackViewModel(PlaybackController(engine))
            val viewModel = StarredViewModel(starredRepository(songs), playback, Application())
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

    private fun starredRepository(songs: Flow<List<Song>>): SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getStarredSongs" -> songs
            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository

    private fun starredSong(id: String, dateAdded: Long) = Song(
        id = id,
        title = id,
        artist = "Artist",
        album = "Album",
        duration = 1_000L,
        filePath = "/music/$id.mp3",
        dateAdded = dateAdded,
    )
}
