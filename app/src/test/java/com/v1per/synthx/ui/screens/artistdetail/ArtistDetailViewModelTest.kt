package com.v1per.synthx.ui.screens.artistdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The artist detail stats line and Play/Shuffle buttons are driven by the playable songs only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test
    fun theStatsCountAndTotalOnlyThePlayableSongs() {
        val songs = listOf(
            song("s1", durationMs = 200_000L),
            song("gone", durationMs = 500_000L, missing = true),
            song("s2", durationMs = 100_000L),
            song("silent", durationMs = 0L),
        )
        withViewModel(flowOf(songs)) { vm ->
            val data = assertIs<UiState.Success<ArtistDetailData>>(vm.uiState.value).data
            assertEquals(4, data.songs.size, "the list still shows the missing song")
            assertEquals(3, data.playableSongCount)
            assertEquals(300_000L, data.playableDurationMs)
        }
    }

    @Test
    fun anArtistWithNoSongsIsEmpty() {
        withViewModel(flowOf(emptyList())) { vm ->
            assertIs<UiState.Empty>(vm.uiState.value)
        }
    }

    @Test
    fun anAllMissingArtistIsSuccessWithNothingPlayable() {
        val songs = listOf(song("gone1", missing = true), song("gone2", missing = true))
        withViewModel(flowOf(songs)) { vm ->
            val data = assertIs<UiState.Success<ArtistDetailData>>(vm.uiState.value).data
            assertEquals(2, data.songs.size)
            assertEquals(0, data.playableSongCount)
            assertEquals(0L, data.playableDurationMs)
        }
    }

    @Test
    fun songsKeepTheRepositorysOrder() {
        val songs = listOf(song("c"), song("a", missing = true), song("b"))
        withViewModel(flowOf(songs)) { vm ->
            val data = assertIs<UiState.Success<ArtistDetailData>>(vm.uiState.value).data
            assertEquals(listOf("c", "a", "b"), data.songs.map { it.id })
        }
    }

    private fun withViewModel(songs: Flow<List<Song>>, block: (ArtistDetailViewModel) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = ArtistDetailViewModel(
                repository = fakeRepository(songs),
                artistId = "a1",
                appContext = Application(),
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

    private fun fakeRepository(songs: Flow<List<Song>>): SongRepository =
        Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSongsForArtist" -> {
                    assertEquals("a1", args[0])
                    songs
                }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository

    private fun song(id: String, durationMs: Long = 1_000L, missing: Boolean = false) = Song(
        id = id, title = id, artist = "Artist", album = "Album", duration = durationMs, filePath = "/music/$id.mp3",
        isMissing = missing,
    )
}
