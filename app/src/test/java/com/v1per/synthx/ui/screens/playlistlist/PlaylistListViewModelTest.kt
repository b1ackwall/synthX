package com.v1per.synthx.ui.screens.playlistlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.common.UiState
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistListViewModelTest {

    @Test
    fun bothListsPopulatedProduceSuccess() {
        val group = PlaylistGroup(id = "g1", name = "Group One")
        val playlist = playlist("p1")
        withViewModel(
            groups = flowOf(listOf(group)),
            ungroupedPlaylists = flowOf(listOf(playlist)),
        ) { viewModel ->
            val state = assertIs<UiState.Success<PlaylistListData>>(viewModel.uiState.value)
            assertEquals(listOf(group), state.data.groups)
            assertEquals(listOf(playlist), state.data.ungroupedPlaylists)
        }
    }

    @Test
    fun bothListsEmptyProduceEmpty() {
        withViewModel(
            groups = flowOf(emptyList()),
            ungroupedPlaylists = flowOf(emptyList()),
        ) { viewModel ->
            assertIs<UiState.Empty>(viewModel.uiState.value)
        }
    }

    @Test
    fun failingGroupsFlowProducesError() {
        withViewModel(
            groups = flow { throw RuntimeException("query failed") },
            ungroupedPlaylists = flowOf(emptyList()),
        ) { viewModel ->
            val state = assertIs<UiState.Error>(viewModel.uiState.value)
            assertEquals("Couldn't load your playlists.", state.message)
        }
    }

    private fun withViewModel(
        groups: Flow<List<PlaylistGroup>>,
        ungroupedPlaylists: Flow<List<Playlist>>,
        block: (PlaylistListViewModel) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = PlaylistListViewModel(
                repository = fakeRepository(groups, ungroupedPlaylists),
                appContext = Application(),
                stringResource = { id ->
                    when (id) {
                        R.string.error_load_playlists_list -> "Couldn't load your playlists."
                        else -> error("Unexpected string resource: $id")
                    }
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

    private fun fakeRepository(
        groups: Flow<List<PlaylistGroup>>,
        ungroupedPlaylists: Flow<List<Playlist>>,
    ): SongRepository {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAllGroups" -> groups
                "getUngroupedPlaylists" -> ungroupedPlaylists
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository
    }

    private fun playlist(id: String) = Playlist(
        id = id,
        name = id,
        groupId = null,
        albumArtUri = null,
    )
}
