package com.v1per.synthx.ui.screens.playlistlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistListViewModelTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

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

    // ---- createGroup guard (P8-T7 / B06) --------------------------------------------------

    @Test
    fun repeatSubmitWhileTheFirstWriteIsInFlightCreatesExactlyOneGroup() {
        val gate = CompletableDeferred<Unit>()
        val repository = RecordingGroupRepository(gate = gate)
        withViewModel(repositoryWrapper = repository::wrapping) { viewModel ->
            viewModel.onCreateGroupClick()

            viewModel.createGroup("x")
            viewModel.createGroup("x") // a button tap and an IME Done landing back to back

            assertEquals(listOf("x"), repository.created)
            assertTrue(viewModel.isCreatingGroup.value)
            assertTrue(viewModel.showCreateGroup.value, "the card stays open while the write runs")

            gate.complete(Unit)
            assertFalse(viewModel.isCreatingGroup.value)
            assertFalse(viewModel.showCreateGroup.value, "the card closes once the write succeeds")
        }
    }

    @Test
    fun aSubmitLandingInTheExitAnimationAfterSuccessIsRefused() {
        // The write finishes instantly here, so the busy flag is already clear again by the time
        // the second call arrives — only the closed-card check can stop it. That is the state the
        // card is in for its ~150 ms exit animation, still composed with its button enabled.
        val repository = RecordingGroupRepository(gate = null)
        withViewModel(repositoryWrapper = repository::wrapping) { viewModel ->
            viewModel.onCreateGroupClick()

            viewModel.createGroup("x")
            assertFalse(viewModel.showCreateGroup.value)
            viewModel.createGroup("x")

            assertEquals(listOf("x"), repository.created)
        }
    }

    @Test
    fun aFailedCreateKeepsTheCardOpenReportsTheErrorAndCanBeRetried() {
        val repository = RecordingGroupRepository(gate = null, failure = RuntimeException("disk full"))
        val errors = mutableListOf<PlaylistListViewModel.ActionError>()
        withViewModel(repositoryWrapper = repository::wrapping) { viewModel ->
            val collector = CoroutineScope(Dispatchers.Unconfined).launch {
                viewModel.actionErrors.collect { errors += it }
            }
            try {
                viewModel.onCreateGroupClick()
                viewModel.createGroup("x")

                assertEquals(listOf(PlaylistListViewModel.ActionError.CreateGroupFailed), errors)
                assertTrue(viewModel.showCreateGroup.value, "a failure must not discard the card and its draft")
                assertFalse(viewModel.isCreatingGroup.value, "the busy flag must clear on failure")

                repository.failure = null
                viewModel.createGroup("x")

                assertEquals(listOf("x", "x"), repository.created)
                assertFalse(viewModel.showCreateGroup.value)
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun blankNamesAndAClosedCardNeverReachTheRepository() {
        val repository = RecordingGroupRepository(gate = null)
        withViewModel(repositoryWrapper = repository::wrapping) { viewModel ->
            viewModel.createGroup("x") // card never opened
            viewModel.onCreateGroupClick()
            viewModel.createGroup("   ")
            viewModel.createGroup("")

            assertEquals(emptyList(), repository.created)
            assertTrue(viewModel.showCreateGroup.value)
            assertFalse(viewModel.isCreatingGroup.value)
        }
    }

    @Test
    fun theNameIsTrimmedBeforeItIsWritten() {
        val repository = RecordingGroupRepository(gate = null)
        withViewModel(repositoryWrapper = repository::wrapping) { viewModel ->
            viewModel.onCreateGroupClick()
            viewModel.createGroup("  Road trip  ")

            assertEquals(listOf("Road trip"), repository.created)
        }
    }

    /**
     * Real [SongRepository] behaviour for the group write only; everything else falls through to
     * the Proxy the load-path tests already use. A suspending Proxy handler cannot be written
     * (it would have to return COROUTINE_SUSPENDED), so the suspend method is overridden here.
     */
    private class RecordingGroupRepository(
        private val gate: CompletableDeferred<Unit>?,
        var failure: Throwable? = null,
    ) {
        val created = mutableListOf<String>()

        fun wrapping(delegate: SongRepository): SongRepository = object : SongRepository by delegate {
            override suspend fun createGroup(name: String) {
                created += name
                gate?.await()
                failure?.let { throw it }
            }
        }
    }

    private fun withViewModel(
        groups: Flow<List<PlaylistGroup>> = flowOf(emptyList()),
        ungroupedPlaylists: Flow<List<Playlist>> = flowOf(emptyList()),
        repositoryWrapper: (SongRepository) -> SongRepository = { it },
        block: (PlaylistListViewModel) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = PlaylistListViewModel(
                repository = repositoryWrapper(fakeRepository(groups, ungroupedPlaylists)),
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
