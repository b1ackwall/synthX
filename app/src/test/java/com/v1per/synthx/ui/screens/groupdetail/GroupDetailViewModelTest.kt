package com.v1per.synthx.ui.screens.groupdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.screens.groupdetail.GroupDetailViewModel.ActionError
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the group page shows for its playlists and group list, and how its playlist actions reach
 * the repository. An unknown group id is just an empty list, so it lands on Empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val one = Playlist("p1", "One", groupId = "g1", albumArtUri = null)
    private val two = Playlist("p2", "Two", groupId = "g1", albumArtUri = null)

    @Test
    fun aGroupWithPlaylistsIsSuccess() {
        withViewModel(playlists = flowOf(listOf(one, two))) { vm, _ ->
            val data = assertIs<UiState.Success<List<Playlist>>>(vm.uiState.value).data
            assertEquals(listOf("p1", "p2"), data.map { it.id })
        }
    }

    @Test
    fun anEmptyListIsEmptyWhichIsWhatAnUnknownGroupIdProduces() {
        withViewModel(playlists = flowOf(emptyList())) { vm, _ ->
            assertIs<UiState.Empty>(vm.uiState.value)
        }
    }

    @Test
    fun theStateFollowsTheListWhenItChanges() {
        val playlists = MutableStateFlow(listOf(one))
        withViewModel(playlists = playlists) { vm, _ ->
            assertIs<UiState.Success<List<Playlist>>>(vm.uiState.value)

            playlists.value = emptyList()

            assertIs<UiState.Empty>(vm.uiState.value)
        }
    }

    @Test
    fun theGroupListIsExposedUnfiltered() {
        val groups = listOf(PlaylistGroup("g1", "This"), PlaylistGroup("g2", "Other"))
        withViewModel(groups = flowOf(groups)) { vm, _ ->
            assertEquals(groups, vm.groups.value)
        }
    }

    @Test
    fun aFailingGroupListLeavesEmptyGroupsAndTheListIntact() {
        withViewModel(
            playlists = flowOf(listOf(one)),
            groups = flow {
                throw RuntimeException("groups failed")
            },
        ) { vm, _ ->
            assertTrue(vm.groups.value.isEmpty())
            assertIs<UiState.Success<List<Playlist>>>(vm.uiState.value)
        }
    }

    @Test
    fun aBlankRenameIsIgnored() {
        withViewModel { vm, repo ->
            vm.renamePlaylist("p1", "   ")

            assertTrue(repo.calls.isEmpty(), "a blank name must not reach the repository: ${repo.calls}")
            assertTrue(repo.errors.isEmpty())
        }
    }

    @Test
    fun aRenameIsTrimmedBeforeItReachesTheRepository() {
        withViewModel { vm, repo ->
            vm.renamePlaylist("p1", "  New name  ")

            assertEquals(listOf("renamePlaylist(p1, New name)"), repo.calls)
        }
    }

    @Test
    fun aFailedRenameEmitsRenameFailed() {
        withViewModel(failing = "renamePlaylist") { vm, repo ->
            vm.renamePlaylist("p1", "New")

            assertEquals(listOf(ActionError.RenameFailed), repo.errors)
        }
    }

    @Test
    fun aFailedDeleteEmitsDeleteFailed() {
        withViewModel(failing = "deletePlaylist") { vm, repo ->
            vm.deletePlaylist("p1")

            assertEquals(listOf(ActionError.DeleteFailed), repo.errors)
        }
    }

    @Test
    fun deleteReachesTheRepository() {
        withViewModel { vm, repo ->
            vm.deletePlaylist("p1")

            assertEquals(listOf("deletePlaylist(p1)"), repo.calls)
            assertTrue(repo.errors.isEmpty())
        }
    }

    @Test
    fun removeFromGroupMovesThePlaylistToNoGroup() {
        withViewModel { vm, repo ->
            vm.removeFromGroup("p1")

            assertEquals(listOf("movePlaylistToGroup(p1, null)"), repo.calls)
        }
    }

    @Test
    fun aFailedRemoveFromGroupEmitsRemoveFromGroupFailed() {
        withViewModel(failing = "movePlaylistToGroup") { vm, repo ->
            vm.removeFromGroup("p1")

            assertEquals(listOf(ActionError.RemoveFromGroupFailed), repo.errors)
        }
    }

    @Test
    fun moveToGroupMovesThePlaylistToTheTargetGroup() {
        withViewModel { vm, repo ->
            vm.moveToGroup("p1", "g2")

            assertEquals(listOf("movePlaylistToGroup(p1, g2)"), repo.calls)
        }
    }

    @Test
    fun aFailedMoveEmitsMoveToGroupFailed() {
        withViewModel(failing = "movePlaylistToGroup") { vm, repo ->
            vm.moveToGroup("p1", "g2")

            assertEquals(listOf(ActionError.MoveToGroupFailed), repo.errors)
        }
    }

    private class Recorder {
        val calls = mutableListOf<String>()
        val errors = mutableListOf<ActionError>()
    }

    private fun withViewModel(
        playlists: Flow<List<Playlist>> = flowOf(emptyList()),
        groups: Flow<List<PlaylistGroup>> = flowOf(emptyList()),
        failing: String? = null,
        block: (GroupDetailViewModel, Recorder) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val recorder = Recorder()
        try {
            val viewModel = GroupDetailViewModel(
                repository = fakeRepository(playlists, groups, failing, recorder),
                groupId = "g1",
                appContext = Application(),
            )
            val collector = CoroutineScope(UnconfinedTestDispatcher())
            collector.launch { viewModel.actionErrors.collect { recorder.errors += it } }
            try {
                block(viewModel, recorder)
            } finally {
                collector.cancel()
                viewModel.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun fakeRepository(
        playlists: Flow<List<Playlist>>,
        groups: Flow<List<PlaylistGroup>>,
        failing: String?,
        recorder: Recorder,
    ): SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getPlaylistsForGroup" -> playlists

            "getAllGroups" -> groups

            "renamePlaylist", "deletePlaylist", "movePlaylistToGroup" -> {
                // The last argument of a suspend function is its Continuation.
                recorder.calls += "${method.name}(${args.dropLast(1).joinToString(", ")})"
                if (method.name == failing) error("$failing failed")
                Unit
            }

            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository
}
