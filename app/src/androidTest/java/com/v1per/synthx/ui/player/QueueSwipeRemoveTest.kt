package com.v1per.synthx.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Swipe-to-remove through the real Up Next content: the slot id the host hands to the engine's
 * remove call must be the swiped row's, also when the queue changes while the finger is down.
 *
 * [onQueueRemove] records the id and applies the removal to [queue] the way the engine's echo
 * would. A swipe that removes nothing is acceptable under a concurrent change; removing any
 * other row is not.
 */
@RunWith(AndroidJUnit4::class)
class QueueSwipeRemoveTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val queue = mutableStateOf<List<QueueSlot>>(emptyList())
    private val removed = mutableListOf<String>()

    private fun song(id: String) = Song(id, "Title $id", "Artist", "Album", 1000L, "audio/$id")

    private fun slot(id: String) = QueueSlot(id, song(id))

    private fun setUp(vararg ids: String) {
        queue.value = ids.map { slot(it) }
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                PlayerSkeletonQueueContent(
                    song = song("current"),
                    artOffsetYQueue = 0.dp,
                    queueListOffsetY = 120.dp,
                    headerRegionHeight = 120.dp,
                    queueListState = rememberLazyListState(),
                    queueScrollConnection = object : NestedScrollConnection {},
                    expandedContentAlpha = 1f,
                    queueProgressValue = 1f,
                    expandedContentEnabled = true,
                    queueModeArtSize = 48.dp,
                    upcomingQueue = queue.value,
                    shuffleEnabled = false,
                    repeatMode = RepeatMode.Off,
                    onShuffleClick = {},
                    onRepeatClick = {},
                    onQueueSongClick = {},
                    onQueueMove = { _, _ -> },
                    onQueueRemove = { slotId ->
                        removed += slotId
                        queue.value = queue.value.filterNot { it.slotId == slotId }
                    },
                    controlsOverlayHeight = 0.dp,
                    queueFadeAlpha = 1f,
                    controlsOverlayAlpha = 1f,
                    controlsOverlaySlide = 0.dp,
                    bottomInset = 0.dp,
                    fullHeight = 700.dp,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun row(id: String) = composeRule.onNode(hasText("Title $id"))

    /** Presses the row's title and drags it left by [fraction] of its width, a frame at a time. */
    private fun SemanticsNodeInteraction.dragLeft(fraction: Float) {
        val width = fetchSemanticsNode().size.width.toFloat()
        val step = -width * fraction / SWIPE_STEPS
        repeat(SWIPE_STEPS) {
            performTouchInput { moveBy(Offset(step, 0f)) }
            composeRule.waitForIdle()
        }
    }

    private fun SemanticsNodeInteraction.press() {
        performTouchInput { down(center) }
        composeRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.release() {
        performTouchInput { up() }
        composeRule.waitForIdle()
    }

    private fun swipe(id: String, midSwipe: () -> Unit = {}) {
        row(id).apply {
            press()
            dragLeft(FIRST_HALF)
            midSwipe()
            dragLeft(SECOND_HALF)
            release()
        }
    }

    private fun mutateQueue(change: (List<QueueSlot>) -> List<QueueSlot>) {
        composeRule.runOnIdle { queue.value = change(queue.value) }
        composeRule.waitForIdle()
    }

    private fun assertOnlyRemoved(id: String) {
        composeRule.runOnIdle {
            assertTrue("Removed $removed, expected at most [$id]", removed.all { it == id })
            assertTrue("$id must be gone or untouched", removed.size <= 1)
        }
    }

    @Test fun swipingARowAwayRemovesThatSlot() {
        setUp("a", "b", "c")
        swipe("b")
        composeRule.runOnIdle {
            assertEquals(listOf("b"), removed.toList())
            assertEquals(listOf("a", "c"), queue.value.map { it.slotId })
        }
    }

    @Test fun swipingTheFirstRowRemovesThatSlot() {
        setUp("a", "b", "c")
        swipe("a")
        composeRule.runOnIdle { assertEquals(listOf("a"), removed.toList()) }
    }

    @Test fun aRowInsertedAboveMidSwipeDoesNotRedirectTheRemoval() {
        setUp("a", "b", "c")
        swipe("b") { mutateQueue { listOf(slot("x")) + it } }
        assertOnlyRemoved("b")
    }

    @Test fun theQueueAdvancingMidSwipeDoesNotRedirectTheRemoval() {
        setUp("a", "b", "c")
        swipe("c") { mutateQueue { it.drop(1) } }
        assertOnlyRemoved("c")
    }

    @Test fun anotherRowRemovedMidSwipeDoesNotRedirectTheRemoval() {
        setUp("a", "b", "c")
        swipe("c") { mutateQueue { it.filterNot { slot -> slot.slotId == "a" } } }
        assertOnlyRemoved("c")
    }

    private companion object {
        const val SWIPE_STEPS = 5
        const val FIRST_HALF = 0.3f
        const val SECOND_HALF = 0.5f
    }
}
