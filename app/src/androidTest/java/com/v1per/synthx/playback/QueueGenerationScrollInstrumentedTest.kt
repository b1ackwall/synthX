package com.v1per.synthx.playback

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.player.QueueModeState
import com.v1per.synthx.ui.player.rememberQueueModeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Up Next's scroll-to-top on the real engine and view model: the shuffle toggle reorders the same
 * slots and bumps the generation, and the list, fed by the view model the way the player sheet is,
 * opens at its first row in the new order. An edit that does not bump keeps the place.
 */
@RunWith(AndroidJUnit4::class)
class QueueGenerationScrollInstrumentedTest : PlaybackServiceTestBase() {

    @get:Rule val composeRule = createComposeRule()

    private var state: QueueModeState? = null

    private fun showUpNext(viewModel: PlaybackViewModel) {
        composeRule.setContent {
            val upcoming by viewModel.upcomingQueue.collectAsState()
            val generation by viewModel.queueGeneration.collectAsState()
            val s = rememberQueueModeState(rememberCoroutineScope(), upcoming, generation, onShuffleClick = {})
            SideEffect { state = s }
            LazyColumn(state = s.queueListState, modifier = Modifier.height(400.dp).testTag(LIST)) {
                items(upcoming, key = { it.slotId }) { Text(it.song.title, Modifier.height(64.dp)) }
            }
        }
        composeRule.waitUntil(15_000L) {
            composeRule.runOnIdle { requireNotNull(state).queueListState.layoutInfo.totalItemsCount } >
                60
        }
    }

    private fun firstVisibleIndex(): Int = composeRule.runOnIdle {
        requireNotNull(state).queueListState.firstVisibleItemIndex
    }

    private fun firstVisibleSlot(): String {
        val index = firstVisibleIndex()
        return onMain { engine._upcomingQueue.value[index].slotId }
    }

    private fun scrollTo(index: Int) {
        composeRule.onNodeWithTag(LIST).performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    @Test fun aShuffleToggleOpensUpNextAtTheTopOfTheNewOrder() {
        startQueue(101, 0, RepeatMode.Off)
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }
        showUpNext(viewModel)
        scrollTo(60)

        onMain { viewModel.toggleShuffle() }
        await("shuffle on") { onMain { engine._shuffleEnabled.value } }
        settle()
        composeRule.waitForIdle()

        assertEquals(0, firstVisibleIndex())
    }

    @Test fun aFreshPlayOpensUpNextAtTheTop() {
        startQueue(101, 0, RepeatMode.Off)
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }
        showUpNext(viewModel)
        scrollTo(60)

        onMain { viewModel.requestPlayQueue(songs(101, "fresh"), 0) }
        await("the new queue plays") { onMain { engine.currentSongId.now() == "fresh-0" } }
        settle()
        composeRule.waitForIdle()

        assertEquals(0, firstVisibleIndex())
    }

    @Test fun aPlayNextKeepsThePlace() {
        startQueue(101, 0, RepeatMode.Off)
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }
        showUpNext(viewModel)
        scrollTo(60)
        val before = firstVisibleSlot()

        onMain { engine.playNext(song("inserted")) }
        settle()
        composeRule.waitForIdle()

        assertEquals(before, firstVisibleSlot())
    }

    private companion object {
        const val LIST = "real_engine_up_next"
    }
}
