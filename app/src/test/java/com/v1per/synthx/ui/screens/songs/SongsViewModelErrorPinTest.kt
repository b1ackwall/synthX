package com.v1per.synthx.ui.screens.songs

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.FailOnceFlow
import com.v1per.synthx.ui.testing.RecordingPlaybackEngine
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.withUncaughtExceptionsCaptured
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PINNED (bug "Search, Songs and Starred stop updating after one error"): after one failure of
 * the songs flow, the Songs list never asks the repository again and ignores a new sort order.
 *
 * On a device the screen shows Error. On the JVM the error text cannot be resolved
 * (Context.getString is a stub returning null), so the catch block throws and the last list
 * stays. Both readings are accepted; neither ever shows newer data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SongsViewModelErrorPinTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test
    fun pinnedAfterOneSongsFlowErrorTheListStopsUpdating() = withUncaughtExceptionsCaptured {
        val first = listOf(
            listSong("a", title = "b-title", artist = "A", album = "A", dateAdded = 2L),
            listSong("b", title = "a-title", artist = "A", album = "A", dateAdded = 1L),
        )
        val later = first + listSong("c", title = "c-title", artist = "A", album = "A", dateAdded = 3L)
        val songs = FailOnceFlow(first = first, afterRetry = later)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val playback = PlaybackViewModel(PlaybackController(RecordingPlaybackEngine()))
            val vm = SongsViewModel(songsRepository(songs.flow), playback, Application())
            try {
                val before = runBlocking { withTimeout(2_000) { vm.uiState.first { it is UiState.Success } } }

                songs.fail()
                Thread.sleep(200)
                vm.setSortOrder(SortOrder.TITLE)
                Thread.sleep(500)

                assertEquals(1, songs.subscriptions, "the songs flow was never subscribed again")
                val after = vm.uiState.value
                assertTrue(
                    after is UiState.Error || after == before,
                    "expected Error (device) or the frozen pre-error list (JVM), got $after",
                )
            } finally {
                vm.viewModelScope.cancel()
                playback.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
