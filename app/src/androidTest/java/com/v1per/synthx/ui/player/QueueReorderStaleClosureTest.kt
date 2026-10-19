package com.v1per.synthx.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.QueueList
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drag-reorder through the real reorderable handle gesture. The handle keeps the callbacks of
 * its first touch until another row is dragged, so a queue change between two touches of the
 * same handle must not leave its drop reading an outdated list. A queue change while the finger is
 * down must neither drop a real move nor send one for a drag that ended where the row already is.
 *
 * [onMove] here applies the move to [queue] the way the engine's echo would, so every committed
 * drop is followed by a queue change. Drags land between swap thresholds (a row's swap fires once
 * the dragged row covers its center) so touch slop never decides the outcome.
 */
@RunWith(AndroidJUnit4::class)
class QueueReorderStaleClosureTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val queue = mutableStateOf<ImmutableList<QueueSlot>>(persistentListOf())
    private val enabled = mutableStateOf(true)
    private val moves = mutableListOf<Pair<String, Int>>()

    private val handleDescription =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_drag_handle)

    private fun slot(id: String) = QueueSlot(id, Song(id, "Title $id", "Artist", "Album", 1000L, "audio/$id"))

    private fun setUp(vararg ids: String) {
        queue.value = ids.map { slot(it) }.toPersistentList()
        composeRule.setContent {
            QueueList(
                queue = queue.value,
                onSongClick = {},
                onMove = { slotId, toIndex ->
                    moves += slotId to toIndex
                    queue.value = queue.value.toMutableList().apply {
                        add(toIndex, removeAt(indexOfFirst { it.slotId == slotId }))
                    }.toPersistentList()
                },
                onRemove = {},
                enabled = enabled.value,
            )
        }
        composeRule.waitForIdle()
    }

    private fun row(id: String) = composeRule.onNode(hasText("Title $id"))

    private fun handle(id: String) =
        composeRule.onNode(hasContentDescription(handleDescription) and hasParent(hasText("Title $id")))

    private fun rowHeight(): Float = row(queue.value.first().slotId).fetchSemanticsNode().size.height.toFloat()

    /** Top-to-bottom order of the rows as drawn. */
    private fun displayedOrder(): List<String> =
        queue.value.map { it.slotId }.sortedBy { row(it).fetchSemanticsNode().boundsInRoot.top }

    private fun SemanticsNodeInteraction.pressHandle() {
        performTouchInput { down(center) }
        composeRule.waitForIdle()
    }

    /** Moves an already-pressed pointer by [rows] row heights, a frame at a time. */
    private fun SemanticsNodeInteraction.moveRows(rows: Float) {
        val step = rowHeight() * rows / MOVE_STEPS
        repeat(MOVE_STEPS) {
            performTouchInput { moveBy(Offset(0f, step)) }
            composeRule.waitForIdle()
        }
    }

    private fun SemanticsNodeInteraction.release() {
        performTouchInput { up() }
        composeRule.waitForIdle()
    }

    private fun drag(id: String, rows: Float) {
        handle(id).apply {
            pressHandle()
            moveRows(rows)
            release()
        }
    }

    private fun skip() {
        composeRule.runOnIdle { queue.value = queue.value.drop(1).toPersistentList() }
        composeRule.waitForIdle()
    }

    /** Play-next or skip-previous: a row lands above every existing row. */
    private fun insertAtHead(id: String) {
        composeRule.runOnIdle { queue.value = (listOf(slot(id)) + queue.value).toPersistentList() }
        composeRule.waitForIdle()
    }

    private fun append(id: String) {
        composeRule.runOnIdle { queue.value = (queue.value + slot(id)).toPersistentList() }
        composeRule.waitForIdle()
    }

    private fun assertMoves(vararg expected: Pair<String, Int>) {
        composeRule.runOnIdle { assertEquals(expected.toList(), moves.toList()) }
    }

    @Test fun sameHandleDraggedTwiceCommitsBothDrops() {
        setUp("a", "b", "c", "d", "e")
        drag("a", 2f)
        assertMoves("a" to 2)
        assertEquals(listOf("b", "c", "a", "d", "e"), displayedOrder())

        drag("a", 2f)
        assertMoves("a" to 2, "a" to 4)
        assertEquals(listOf("b", "c", "d", "e", "a"), displayedOrder())
    }

    @Test fun dragAfterDropAndSkipCommits() {
        setUp("a", "b", "c", "d", "e")
        drag("a", 2f)
        assertMoves("a" to 2)

        skip()
        assertEquals(listOf("c", "a", "d", "e"), displayedOrder())
        drag("a", 2f)
        assertMoves("a" to 2, "a" to 3)
        assertEquals(listOf("c", "d", "e", "a"), displayedOrder())
    }

    @Test fun firstDragAfterTapAndSkipCommits() {
        // "b", not "a": the skip removes the first row.
        setUp("a", "b", "c", "d", "e")
        handle("b").apply {
            pressHandle()
            release()
        }
        assertMoves()

        skip()
        drag("b", 2f)
        assertMoves("b" to 2)
        assertEquals(listOf("c", "d", "b", "e"), displayedOrder())
    }

    @Test fun disabledDropMovesNothingAndRestoresTheEngineOrder() {
        enabled.value = false
        setUp("a", "b", "c", "d", "e")
        handle("a").apply {
            pressHandle()
            moveRows(2f)
        }
        // The library's live swap did happen; only the drop is refused.
        assertEquals(listOf("b", "c", "a", "d", "e"), displayedOrder())

        handle("a").release()
        assertMoves()
        assertEquals(listOf("a", "b", "c", "d", "e"), displayedOrder())
    }

    @Test fun dropAtStartPositionMovesNothing() {
        setUp("a", "b", "c", "d", "e")
        handle("a").apply {
            pressHandle()
            moveRows(1f)
        }
        assertEquals(listOf("b", "a", "c", "d", "e"), displayedOrder())

        handle("a").apply {
            moveRows(-1f)
            release()
        }
        assertMoves()
        assertEquals(listOf("a", "b", "c", "d", "e"), displayedOrder())
    }

    // A queue change while the finger is down swaps the list under the drag. The dragged row
    // stays under the finger, so the next move re-places it among the new rows.

    @Test fun skipMidDragStillCommitsTheMove() {
        setUp("a", "b", "c", "d", "e")
        handle("c").apply {
            pressHandle()
            moveRows(1f)
        }
        skip()
        // Back up one row: "c" ends just below "d", one row further than in the new queue.
        handle("c").apply {
            moveRows(-1f)
            release()
        }
        assertMoves("c" to 2)
        assertEquals(listOf("b", "d", "c", "e"), displayedOrder())
    }

    @Test fun skipMidDragWithoutNetMoveSendsNothing() {
        setUp("a", "b", "c", "d", "e")
        handle("c").apply {
            pressHandle()
            moveRows(PAST_SLOP_ROWS)
        }
        skip()
        handle("c").release()
        assertMoves()
        assertEquals(listOf("b", "c", "d", "e"), displayedOrder())
    }

    @Test fun headInsertMidDragWithoutNetMoveSendsNothing() {
        setUp("a", "b", "c", "d", "e")
        handle("c").apply {
            pressHandle()
            moveRows(PAST_SLOP_ROWS)
        }
        insertAtHead("z")
        handle("c").release()
        assertMoves()
        assertEquals(listOf("z", "a", "b", "c", "d", "e"), displayedOrder())
    }

    @Test fun headInsertMidDragStillCommitsTheMove() {
        setUp("a", "b", "c", "d", "e")
        handle("c").apply {
            pressHandle()
            moveRows(-1f)
        }
        insertAtHead("z")
        // Down one row: "c" ends just above "b", where it was dropped before the insert.
        handle("c").apply {
            moveRows(1f)
            release()
        }
        assertMoves("c" to 2)
        assertEquals(listOf("z", "a", "c", "b", "d", "e"), displayedOrder())
    }

    @Test fun draggedRowRemovedMidDragEndsTheDragCleanly() {
        setUp("a", "b", "c", "d", "e")
        handle("a").apply {
            pressHandle()
            moveRows(PAST_SLOP_ROWS)
        }
        skip()
        // The dragged row's handle is gone; lift the pointer wherever it is.
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()
        assertMoves()

        drag("b", 2f)
        assertMoves("b" to 2)
        assertEquals(listOf("c", "d", "b", "e"), displayedOrder())
    }

    @Test fun appendMidDragStillCommitsTheMove() {
        setUp("a", "b", "c", "d", "e")
        handle("c").apply {
            pressHandle()
            moveRows(1f)
        }
        append("f")
        // The new list resets the live swaps; the next finger move re-places "c" under it.
        handle("c").apply {
            moveRows(NUDGE_ROWS)
            release()
        }
        assertMoves("c" to 3)
        assertEquals(listOf("a", "b", "d", "c", "e", "f"), displayedOrder())
    }

    private companion object {
        const val MOVE_STEPS = 12

        /** Past touch slop, short of the half-row that swaps a neighbour. */
        const val PAST_SLOP_ROWS = 0.3f

        /** A small finger move that lets the library re-place the dragged row. */
        const val NUDGE_ROWS = 0.1f
    }
}
