package com.v1per.synthx.ui.screens.playlistdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
 * The detail screen's cover art has to arrive through the same combine as the songs, so the
 * screen is never "ready" with songs and a placeholder that then flips to the art.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private val withArt = Playlist(
        id = "p1", name = "One", groupId = null, albumArtUri = null, artHue = 120, artStructureSeed = 42L,
    )

    @Test
    fun readyWithTheArtAlreadyAttached() {
        withViewModel(songs = flowOf(listOf(song("s1"))), allPlaylists = MutableStateFlow(listOf(withArt))) { vm ->
            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertEquals(withArt, data.playlist)
            assertEquals(listOf("s1"), data.songs.map { it.id })
        }
    }

    @Test
    fun theRepositorysEmptySeedIsNotMistakenForAMissingPlaylist() {
        // getAllPlaylists() is a StateFlow seeded with an empty list before its first query
        // lands. If that seed were passed through, the screen would go ready with playlist = null
        // (placeholder) and then flip to the art a moment later.
        val allPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
        withViewModel(songs = flowOf(listOf(song("s1"))), allPlaylists = allPlaylists) { vm ->
            assertIs<UiState.Loading>(vm.uiState.value, "must wait for the real playlist list, not the seed")

            allPlaylists.value = listOf(withArt)

            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertEquals(withArt, data.playlist)
        }
    }

    @Test
    fun anEmptyPlaylistIsStillSuccessSoTheCoverCanRenderAboveTheEmptyMessage() {
        withViewModel(songs = flowOf(emptyList()), allPlaylists = MutableStateFlow(listOf(withArt))) { vm ->
            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertTrue(data.songs.isEmpty())
            assertEquals(withArt, data.playlist)
        }
    }

    @Test
    fun aPlaylistWithNoArtYetSucceedsWithNullArtFields() {
        val artless = withArt.copy(artHue = null, artStructureSeed = null)
        withViewModel(songs = flowOf(listOf(song("s1"))), allPlaylists = MutableStateFlow(listOf(artless))) { vm ->
            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertNull(data.playlist?.artHue)
            assertNull(data.playlist?.artStructureSeed)
        }
    }

    @Test
    fun aPlaylistThatLeavesTheLibraryWhileOpenDegradesToNullNotStuckLoading() {
        val allPlaylists = MutableStateFlow(listOf(withArt))
        withViewModel(songs = flowOf(listOf(song("s1"))), allPlaylists = allPlaylists) { vm ->
            assertEquals(withArt, assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data.playlist)

            allPlaylists.value = listOf(withArt.copy(id = "other"))

            assertNull(assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data.playlist)
        }
    }

    @Test
    fun theStatsCountAndTotalOnlyThePlayableSongs() {
        val songs = listOf(
            song("s1", durationMs = 200_000L),
            song("gone", durationMs = 500_000L, missing = true),
            song("s2", durationMs = 100_000L),
            song("silent", durationMs = 0L),
        )
        withViewModel(songs = flowOf(songs), allPlaylists = MutableStateFlow(listOf(withArt))) { vm ->
            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertEquals(4, data.songs.size, "the list still shows the missing song")
            assertEquals(3, data.playableSongCount)
            assertEquals(300_000L, data.playableDurationMs)
        }
    }

    @Test
    fun anAllMissingPlaylistHasNothingPlayable() {
        val songs = listOf(song("gone", missing = true))
        withViewModel(songs = flowOf(songs), allPlaylists = MutableStateFlow(listOf(withArt))) { vm ->
            val data = assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value).data
            assertEquals(0, data.playableSongCount)
            assertEquals(0L, data.playableDurationMs)
        }
    }

    @Test
    fun aFailingSongsFlowStillProducesError() {
        withViewModel(
            songs = flow { throw RuntimeException("query failed") },
            allPlaylists = MutableStateFlow(listOf(withArt)),
                    ) { vm ->
            assertEquals("Couldn't load this playlist.", assertIs<UiState.Error>(vm.uiState.value).message)
        }
    }

    private fun withViewModel(
        songs: Flow<List<Song>>,
        allPlaylists: Flow<List<Playlist>>,
        block: (PlaylistDetailViewModel) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = PlaylistDetailViewModel(
                repository = fakeRepository(songs, allPlaylists),
                playlistId = "p1",
                appContext = Application(),
                stringResource = { id ->
                    if (id == R.string.error_load_playlist) "Couldn't load this playlist." else error("Unexpected string resource: $id")
                },
            )
            try {
                block(viewModel)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun fakeRepository(songs: Flow<List<Song>>, allPlaylists: Flow<List<Playlist>>): SongRepository =
        Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSongsForPlaylist" -> songs
                "getAllPlaylists" -> allPlaylists
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository

    private fun song(id: String, durationMs: Long = 1_000L, missing: Boolean = false) = Song(
        id = id, title = id, artist = "Artist", album = "Album", duration = durationMs, filePath = "/music/$id.mp3",
        isMissing = missing,
    )
}
