package com.v1per.synthx.ui.screens.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.FailOnceFlow
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

/**
 * PINNED (bug "Search, Songs and Starred stop updating after one error"): after one failure of
 * the songs flow, search never asks the repository again and stops answering new queries.
 *
 * On the JVM the error message cannot be resolved (Context.getString is a stub returning null),
 * so the catch block itself throws and the error text is never set. The pin therefore checks
 * only what is the same on a device: no re-subscription and no new results.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelErrorPinTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test
    fun pinnedAfterOneSongsFlowErrorSearchStopsAnsweringNewQueries() = withUncaughtExceptionsCaptured {
        val library = SearchLibrary()
        val drive = searchSong("s-drive", title = "Night Drive", artist = "Kavinsky")
        val songs = FailOnceFlow<List<Song>>(first = library.songs.value, afterRetry = library.songs.value + drive)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val vm = SearchViewModel(library.repository(songs.flow), Application())
            try {
                vm.onQueryChange("night")
                val before = runBlocking { withTimeout(2_000) { vm.results.first { !it.isEmpty } } }
                assertEquals(listOf("s-title", "s-artist"), before.songs.map { it.id })

                songs.fail()
                vm.onQueryChange("drive")
                // Four debounce windows: long enough for a live pipeline to answer "drive".
                Thread.sleep(1_200)

                assertEquals(1, songs.subscriptions, "the songs flow was never subscribed again")
                assertEquals(before, vm.results.value, "results froze at the last answer before the error")
            } finally {
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
