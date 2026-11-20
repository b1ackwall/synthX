package com.v1per.synthx.ui.player

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Up Next scrolls to the top when the queue generation changes (a fresh play installed, a shuffle
 * toggle applied) and at no other time: not for an edit that changes the list without a bump, not
 * when nothing changes, not when the list comes back with the generation it left with. The list here
 * is a plain LazyColumn on [QueueModeState.queueListState], the state the real Up Next list uses.
 */
@RunWith(AndroidJUnit4::class)
class QueueModeGenerationScrollTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private fun slots(prefix: String, count: Int) = (0 until count).map {
        QueueSlot("$prefix-$it", Song("$prefix-$it", "Title $prefix-$it", "A", "B", 1L, "x/$it"))
    }

    private val queue = mutableStateOf(slots("a", 100))
    private val generation = mutableLongStateOf(0L)
    private val shown = mutableStateOf(true)
    private var state: QueueModeState? = null

    @Composable private fun UpNext() {
        val s = rememberQueueModeState(rememberCoroutineScope(), queue.value, generation.longValue, onShuffleClick = {})
        SideEffect { state = s }
        LazyColumn(state = s.queueListState, modifier = Modifier.height(400.dp).testTag(LIST)) {
            items(queue.value, key = { it.slotId }) { Text(it.song.title, Modifier.height(64.dp)) }
        }
    }

    private fun setUp() {
        composeRule.setContent {
            // Keeps the saved state of a host that leaves composition, as an Activity recreation does.
            val holder = rememberSaveableStateHolder()
            if (shown.value) holder.SaveableStateProvider("up-next") { UpNext() }
        }
        composeRule.waitForIdle()
    }

    private fun scrollTo(index: Int) {
        composeRule.onNodeWithTag(LIST).performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    private fun firstVisibleKey(): String = composeRule.runOnIdle {
        val listState = requireNotNull(state).queueListState
        queue.value[listState.firstVisibleItemIndex].slotId
    }

    private fun firstVisibleIndex(): Int = composeRule.runOnIdle {
        requireNotNull(state).queueListState.firstVisibleItemIndex
    }

    @Test fun aFreshQueueWithABumpOpensAtTheTop() {
        setUp()
        scrollTo(60)

        composeRule.runOnIdle {
            queue.value = slots("b", 100)
            generation.longValue++
        }

        assertEquals(0, firstVisibleIndex())
    }

    @Test fun aReorderWithABumpOpensAtTheTopEvenThoughEveryKeyStays() {
        setUp()
        scrollTo(60)

        // A shuffle: the same slots in a new order, published together with the bump.
        composeRule.runOnIdle {
            queue.value = queue.value.reversed()
            generation.longValue++
        }

        assertEquals(0, firstVisibleIndex())
        assertEquals("a-99", firstVisibleKey())
    }

    @Test fun twoBumpsInARowStillEndAtTheTop() {
        setUp()
        scrollTo(60)

        composeRule.runOnIdle { generation.longValue++ }
        scrollTo(40)
        composeRule.runOnIdle {
            queue.value = queue.value.reversed()
            generation.longValue++
        }

        assertEquals(0, firstVisibleIndex())
    }

    @Test fun anEditWithoutABumpKeepsThePlace() {
        setUp()
        scrollTo(60)

        // Play Next and Add to Queue: the list changes, the generation does not.
        composeRule.runOnIdle { queue.value = slots("x", 1) + queue.value + slots("y", 1) }

        assertEquals("a-60", firstVisibleKey())
    }

    @Test fun aNoOpToggleThenATrackChangeDoesNotJump() {
        setUp()
        scrollTo(20)

        // A toggle that changes nothing publishes nothing; a track change drops the first row.
        composeRule.waitForIdle()
        composeRule.runOnIdle { queue.value = queue.value.drop(1) }

        assertEquals("a-20", firstVisibleKey())
    }

    @Test fun comingBackWithTheSameGenerationKeepsThePlace() {
        generation.longValue = 5L
        setUp()
        scrollTo(30)

        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { shown.value = true }

        assertEquals("restored, not scrolled", 30, firstVisibleIndex())
    }

    @Test fun aBumpWhileTheListWasAwayOpensItAtTheTopWhenItComesBack() {
        setUp()
        scrollTo(30)

        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            queue.value = slots("b", 100)
            generation.longValue++
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { shown.value = true }

        assertEquals(0, firstVisibleIndex())
    }

    @Test fun aBumpWhileAFingerScrollsTheListLandsAtTheTopOnceItLetsGo() {
        setUp()
        scrollTo(60)

        // A finger on the list, past the touch slop and still down.
        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(16)
                moveBy(Offset(0f, -12f))
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            queue.value = slots("b", 100)
            generation.longValue++
        }
        composeRule.waitForIdle()
        // Held still before lifting, so the release carries no fling.
        composeRule.onNodeWithTag(LIST).performTouchInput {
            repeat(10) {
                advanceEventTime(16)
                moveBy(Offset.Zero)
            }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, firstVisibleIndex())
    }

    private companion object {
        const val LIST = "generation_scroll_list"
    }
}
