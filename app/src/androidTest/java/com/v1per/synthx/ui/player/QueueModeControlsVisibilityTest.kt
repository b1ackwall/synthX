package com.v1per.synthx.ui.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the empty-queue controls-overlay lockout: [QueueModeState.controlsVisible]'s only
 * gesture path back to `true` runs through [QueueModeState.queueScrollConnection], which is
 * attached to QueueList's modifier — and PlayerSkeletonQueueContent swaps QueueList out of
 * composition for a gesture-less empty-state Box the moment the queue goes empty. A `false`
 * latched at that moment used to be permanent.
 *
 * The hide/show is driven through the real `onPreScroll` connection rather than by poking
 * `controlsVisible` directly, so these tests exercise the same entry point a drag does. The
 * ±1000px deltas are comfortably past the 28dp threshold at every screen density.
 */
@RunWith(AndroidJUnit4::class)
class QueueModeControlsVisibilityTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private fun slot(id: String) =
        QueueSlot(id, Song(id, "Title $id", "Artist", "Album", 1000L, "audio/$id"))

    private fun QueueModeState.drag(deltaY: Float) {
        queueScrollConnection.onPreScroll(Offset(0f, deltaY), NestedScrollSource.UserInput)
    }

    @Test fun emptyingTheQueueRestoresControlsHiddenByAnUpwardDrag() {
        val queue = mutableStateOf(listOf(slot("a"), slot("b")))
        var state: QueueModeState? = null
        composeRule.setContent {
            val s = rememberQueueModeState(rememberCoroutineScope(), queue.value, onShuffleClick = {})
            SideEffect { state = s }
        }

        // Enter queue mode, then hide the overlay exactly as an upward drag on the list would.
        composeRule.runOnIdle { requireNotNull(state).onQueueClick() }
        composeRule.runOnIdle { requireNotNull(state).drag(-1000f) }
        composeRule.runOnIdle { assertFalse(requireNotNull(state).controlsVisible) }

        // Last upcoming song removed (or playback advanced past it) while queue mode is on
        // screen: QueueList leaves composition here, taking the only un-hide path with it.
        composeRule.runOnIdle { queue.value = emptyList() }
        composeRule.runOnIdle {
            assertTrue(
                "Controls must reappear when the queue empties — with no list left to drag on, " +
                    "nothing else can ever set controlsVisible back to true.",
                requireNotNull(state).controlsVisible,
            )
        }
    }

    @Test fun queueEmptyingOutsideQueueModeStillClearsTheLatchBeforeReEntry() {
        val queue = mutableStateOf(listOf(slot("a")))
        var state: QueueModeState? = null
        composeRule.setContent {
            val s = rememberQueueModeState(rememberCoroutineScope(), queue.value, onShuffleClick = {})
            SideEffect { state = s }
        }

        // Hide the overlay, then leave queue mode. isQueueMode is back to false, so
        // PlayerSheetSkeleton's `!isQueueMode` disjunct masks the stale false and the user sees
        // normal controls — but the field itself is still latched, and it survives leaving queue
        // mode, collapsing the sheet, and navigating away (PlayerSheetSkeleton is only
        // zero-sized on those routes, never removed from composition).
        composeRule.runOnIdle { requireNotNull(state).onQueueClick() }
        composeRule.runOnIdle { requireNotNull(state).drag(-1000f) }
        composeRule.runOnIdle { requireNotNull(state).onQueueClick() }
        composeRule.runOnIdle {
            val s = requireNotNull(state)
            assertFalse(s.isQueueMode)
            assertFalse(s.controlsVisible)
        }

        // Queue empties while the user is in Now Playing mode. The reset must not be gated on
        // queue mode, or re-entry below would land straight in the stuck state.
        composeRule.runOnIdle { queue.value = emptyList() }
        composeRule.runOnIdle { requireNotNull(state).onQueueClick() }
        composeRule.runOnIdle {
            val s = requireNotNull(state)
            assertTrue(s.isQueueMode)
            assertTrue(
                "Re-entering queue mode with an already-empty queue must not restore a stale hide.",
                s.controlsVisible,
            )
        }
    }

    @Test fun populatedQueueKeepsItsNormalDirectionalHideAndShow() {
        val queue = mutableStateOf(listOf(slot("a"), slot("b")))
        var state: QueueModeState? = null
        composeRule.setContent {
            val s = rememberQueueModeState(rememberCoroutineScope(), queue.value, onShuffleClick = {})
            SideEffect { state = s }
        }

        composeRule.runOnIdle { requireNotNull(state).onQueueClick() }
        composeRule.runOnIdle { requireNotNull(state).drag(-1000f) }
        composeRule.runOnIdle { assertFalse(requireNotNull(state).controlsVisible) }

        // Queue edits that leave it populated (add, then reorder) must not un-hide anything —
        // the reset is scoped to emptiness alone, not to queue changes in general.
        composeRule.runOnIdle { queue.value = listOf(slot("a"), slot("b"), slot("c")) }
        composeRule.runOnIdle { assertFalse(requireNotNull(state).controlsVisible) }
        composeRule.runOnIdle { queue.value = listOf(slot("c"), slot("a"), slot("b")) }
        composeRule.runOnIdle { assertFalse(requireNotNull(state).controlsVisible) }

        // A downward drag still shows them, and a fresh upward one still hides them.
        composeRule.runOnIdle { requireNotNull(state).drag(1000f) }
        composeRule.runOnIdle { assertTrue(requireNotNull(state).controlsVisible) }
        composeRule.runOnIdle { requireNotNull(state).drag(-1000f) }
        composeRule.runOnIdle { assertFalse(requireNotNull(state).controlsVisible) }
    }
}
