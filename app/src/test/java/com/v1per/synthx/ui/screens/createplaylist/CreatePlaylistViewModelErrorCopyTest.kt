package com.v1per.synthx.ui.screens.createplaylist

import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class CreatePlaylistViewModelErrorCopyTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun aFailingGroupsLoadIsExplainedWithATypographicApostrophe() {
        withViewModel(groups = flow { throw RuntimeException("query failed") }) { viewModel ->
            val state = assertIs<UiState.Error>(viewModel.uiState.value)
            assertEquals("Couldn’t load your groups. Try again.", state.message)
        }
    }

    @Test fun aFailingCreateIsExplainedWithATypographicApostrophe() {
        val failing = object : SongRepository by fakeRepository(flow { emit(emptyList()) }) {
            override suspend fun createPlaylist(name: String, groupId: String?): String =
                throw RuntimeException("write failed")
        }
        withViewModel(repository = failing) { viewModel ->
            viewModel.createPlaylist("Road trip", null)

            assertEquals("Couldn’t create this playlist. Try again.", viewModel.error.value)
        }
    }

    private fun withViewModel(
        groups: Flow<List<PlaylistGroup>> = flow { emit(emptyList()) },
        repository: SongRepository = fakeRepository(groups),
        block: (CreatePlaylistViewModel) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = CreatePlaylistViewModel(repository)
            try {
                block(viewModel)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun fakeRepository(groups: Flow<List<PlaylistGroup>>): SongRepository {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAllGroups" -> groups
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository
    }
}
