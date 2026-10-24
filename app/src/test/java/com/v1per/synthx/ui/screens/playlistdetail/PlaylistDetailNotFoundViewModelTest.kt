package com.v1per.synthx.ui.screens.playlistdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.common.isReady
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the playlist page settles on when the playlist list never produces a row for its id, and
 * that a playlist that exists never passes through the not-found state on the way to its content.
 * The existence answer here is immediate; its timing against the load is covered separately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailNotFoundViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val real = Playlist(
        id = "p1",
        name = "One",
        groupId = null,
        albumArtUri = null,
        artHue = 120,
        artStructureSeed = 42L,
    )

    @Test
    fun anUnknownIdInALibraryWithNoPlaylistsLeavesTheLoadingState() {
        withViewModel(allPlaylists = MutableStateFlow(emptyList()), exists = false) { vm, seen, _ ->
            val state = vm.uiState.value
            assertTrue(state.isReady(), "the page must lift its cover, but the state is $state")
            assertIs<UiState.Empty>(state)
            assertEquals(listOf("Loading", "Empty"), seen.map { it.kind() })
        }
    }

    @Test
    fun aPlaylistWhoseListArrivesLateNeverShowsNotFound() {
        val allPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
        withViewModel(allPlaylists = allPlaylists, exists = true) { vm, seen, settle ->
            assertIs<UiState.Loading>(vm.uiState.value)

            allPlaylists.value = listOf(real)
            settle()

            assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value)
            assertEquals(listOf("Loading", "Success"), seen.map { it.kind() })
        }
    }

    @Test
    fun aRealPlaylistWithNoSongsIsSuccessAndNeverNotFound() {
        val allPlaylists = MutableStateFlow(listOf(real))
        withViewModel(allPlaylists = allPlaylists, exists = true, songs = flowOf(emptyList())) { vm, seen, _ ->
            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertTrue(data.songs.isEmpty())
            assertEquals(real, data.playlist)
            assertTrue(seen.none { it is UiState.Empty })
        }
    }

    @Test
    fun anUnknownIdBesideOtherPlaylistsStillGivesTheNullPlaylistPlaceholder() {
        val other = real.copy(id = "other")
        withViewModel(allPlaylists = MutableStateFlow(listOf(other)), exists = false) { vm, _, _ ->
            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertNull(data.playlist)
        }
    }

    @Test
    fun aPlaylistThatAppearsAfterNotFoundReplacesIt() {
        val allPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
        withViewModel(allPlaylists = allPlaylists, exists = false) { vm, seen, settle ->
            assertIs<UiState.Empty>(vm.uiState.value)

            allPlaylists.value = listOf(real)
            settle()

            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertEquals(real, data.playlist)
            assertEquals(listOf("Loading", "Empty", "Success"), seen.map { it.kind() })
        }
    }

    private fun UiState<*>.kind(): String = when (this) {
        is UiState.Loading -> "Loading"
        is UiState.Empty -> "Empty"
        is UiState.Success -> "Success"
        is UiState.Error -> "Error"
    }

    private fun withViewModel(
        allPlaylists: Flow<List<Playlist>>,
        exists: Boolean,
        songs: Flow<List<Song>> = flowOf(emptyList()),
        block: (PlaylistDetailViewModel, List<UiState<PlaylistDetailData>>, settle: () -> Unit) -> Unit,
    ) {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val recorder = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        try {
            val viewModel = PlaylistDetailViewModel(
                repository = fakeRepository(songs, allPlaylists, exists),
                playlistId = "p1",
                appContext = Application(),
                stringResource = { id -> error("Unexpected string resource: $id") },
            )
            val seen = mutableListOf<UiState<PlaylistDetailData>>()
            recorder.launch { viewModel.uiState.collect { seen += it } }
            scheduler.runCurrent()
            try {
                block(viewModel, seen, scheduler::runCurrent)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            recorder.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun fakeRepository(
        songs: Flow<List<Song>>,
        allPlaylists: Flow<List<Playlist>>,
        exists: Boolean,
    ): SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSongsForPlaylist" -> songs
            "getAllPlaylists" -> allPlaylists
            "playlistExists" -> exists
            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository
}
