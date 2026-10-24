package com.v1per.synthx.ui.screens.playlistdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import kotlin.test.assertTrue

/**
 * How the one-shot existence read relates to the playlist list load: a late "missing" answer never
 * overwrites content, a read that fails or never answers leaves the load in charge, and
 * cancelling the ViewModel cancels the read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailExistenceCheckTest {
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
    private val other = real.copy(id = "other")

    @Test
    fun aMissingAnswerThatArrivesAfterContentLeavesTheContent() {
        val answer = CompletableDeferred<Boolean>()
        withViewModel(listOf(real), answer::await) { vm, seen, settle ->
            assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value)

            answer.complete(false)
            settle()

            assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value)
            assertEquals(listOf("Loading", "Success"), seen.map { it.kind() })
        }
    }

    @Test
    fun aCheckThatNeverAnswersLeavesTheLoadInCharge() {
        withViewModel(emptyList(), { CompletableDeferred<Boolean>().await() }) { vm, _, _ ->
            assertIs<UiState.Loading>(vm.uiState.value)
        }
    }

    @Test
    fun aFailingCheckLeavesLoadingAndTheLoadStillWins() {
        val listArrives = CompletableDeferred<Unit>()
        withViewModel(listOf(real), { error("database unavailable") }, listArrives) { vm, seen, settle ->
            assertIs<UiState.Loading>(vm.uiState.value)

            listArrives.complete(Unit)
            settle()

            assertIs<UiState.Success<PlaylistDetailData>>(vm.uiState.value)
            assertTrue(seen.none { it is UiState.Empty || it is UiState.Error })
        }
    }

    @Test
    fun cancellingTheViewModelCancelsAPendingCheck() {
        val pending = CompletableDeferred<Boolean>()
        var sawCancellation = false
        withViewModel(emptyList(), {
            try {
                pending.await()
            } catch (e: CancellationException) {
                sawCancellation = true
                throw e
            }
        }) { vm, _, settle ->
            vm.viewModelScope.cancel()
            settle()

            assertTrue(sawCancellation, "the pending check must observe the cancellation")
            assertIs<UiState.Loading>(vm.uiState.value)
        }
    }

    @Test
    fun aCancellationThrownByTheCheckEndsItsCoroutineAsCancelled() {
        var checkJob: Job? = null
        withViewModel(emptyList(), {
            checkJob = currentCoroutineContext()[Job]
            throw CancellationException("cancelled from the repository")
        }) { vm, _, _ ->
            assertIs<UiState.Loading>(vm.uiState.value)
            assertTrue(checkJob?.isCancelled == true, "a cancellation must not be treated as a failed read")
        }
    }

    private enum class Library { Zero, Some }

    private enum class Order { LoadThenCheck, CheckThenLoad, CheckNever, LoadNever }

    @Test
    fun everyCombinationOfLibraryPlaylistAndTimingSettlesAsExpected() {
        val wrong = mutableListOf<String>()
        for (library in Library.entries) {
            for (exists in listOf(true, false)) {
                for (order in Order.entries) {
                    val finalList = when (library) {
                        Library.Zero -> if (exists) listOf(real) else emptyList()
                        Library.Some -> if (exists) listOf(real, other) else listOf(other)
                    }
                    val listArrives = CompletableDeferred<Unit>()
                    val answer = CompletableDeferred<Boolean>()
                    withViewModel(finalList, answer::await, listArrives) { vm, seen, settle ->
                        when (order) {
                            Order.LoadThenCheck -> {
                                listArrives.complete(Unit)
                                settle()
                                answer.complete(exists)
                            }

                            Order.CheckThenLoad -> {
                                answer.complete(exists)
                                settle()
                                listArrives.complete(Unit)
                            }

                            Order.CheckNever -> listArrives.complete(Unit)

                            Order.LoadNever -> answer.complete(exists)
                        }
                        settle()
                        val history = seen.joinToString(" > ") { it.kind() }
                        val final = vm.uiState.value.kind()
                        println("MATRIX $library exists=$exists $order: $history")
                        if (exists &&
                            seen.any { it is UiState.Empty }
                        ) {
                            wrong += "real playlist showed Empty: $library $order"
                        }
                        val expected = expectedFinal(library, exists, order)
                        if (final !=
                            expected
                        ) {
                            wrong += "$library exists=$exists $order ended $final, expected $expected"
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), wrong)
    }

    private fun expectedFinal(library: Library, exists: Boolean, order: Order): String = when {
        exists && order == Order.LoadNever -> "Loading"
        exists -> "Success"
        library == Library.Some && order != Order.LoadNever -> "Success"
        order == Order.CheckNever -> "Loading"
        else -> "Empty"
    }

    private fun UiState<*>.kind(): String = when (this) {
        is UiState.Loading -> "Loading"
        is UiState.Empty -> "Empty"
        is UiState.Success -> "Success"
        is UiState.Error -> "Error"
    }

    // The list flow mirrors the repository: an empty seed first, then the real list once it arrives.
    private fun withViewModel(
        finalList: List<Playlist>,
        check: suspend () -> Boolean,
        listArrives: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) },
        block: (PlaylistDetailViewModel, List<UiState<PlaylistDetailData>>, settle: () -> Unit) -> Unit,
    ) {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val recorder = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        try {
            val lists: Flow<List<Playlist>> = flow {
                emit(emptyList())
                listArrives.await()
                emit(finalList)
            }
            val viewModel = PlaylistDetailViewModel(
                repository = fakeRepository(lists, check),
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

    private fun fakeRepository(allPlaylists: Flow<List<Playlist>>, check: suspend () -> Boolean): SongRepository {
        val base = Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSongsForPlaylist" -> flowOf<List<Song>>(emptyList())
                "getAllPlaylists" -> allPlaylists
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SongRepository
        return object : SongRepository by base {
            override suspend fun playlistExists(playlistId: String): Boolean = check()
        }
    }
}
