package com.v1per.synthx.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure decisions behind system transport navigation: [previousAction] (used by
 * `ExoPlayerEngine.previous()`), [navigationCommandsFor] (the availability gate), and the
 * repeat-aware final-item Next rule [nextIndexInOrder] as system transport relies on it.
 */
class SystemNavigationRulesTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val threshold = 3_000L

    private fun action(
        positionMs: Long = 0L,
        loadedIndex: Int,
        repeatMode: RepeatMode = RepeatMode.Off,
        currentOrderIndex: Int,
        orderSize: Int,
    ) = previousAction(positionMs, threshold, loadedIndex, repeatMode, currentOrderIndex, orderSize)

    // --- previousAction --------------------------------------------------------------------

    @Test
    fun `past the threshold restarts even when a previous item is loaded`() {
        assertEquals(
            PreviousAction.RestartCurrent,
            action(positionMs = 3_001L, loadedIndex = 10, currentOrderIndex = 30, orderSize = 200),
        )
    }

    @Test
    fun `at exactly the threshold still steps back`() {
        // media3's own rule: restart only when position is strictly greater than the threshold.
        assertEquals(
            PreviousAction.SeekLoaded(9),
            action(positionMs = threshold, loadedIndex = 10, currentOrderIndex = 30, orderSize = 200),
        )
    }

    @Test
    fun `steps back within the loaded window`() {
        assertEquals(
            PreviousAction.SeekLoaded(0),
            action(loadedIndex = 1, currentOrderIndex = 21, orderSize = 200),
        )
    }

    @Test
    fun `past the threshold restarts at the queue start regardless of repeat all`() {
        assertEquals(
            PreviousAction.RestartCurrent,
            action(
                positionMs = 5_000L,
                loadedIndex = 0,
                repeatMode = RepeatMode.All,
                currentOrderIndex = 0,
                orderSize = 200,
            ),
        )
    }

    @Test
    fun `repeat all at the true queue start wraps to the end`() {
        assertEquals(
            PreviousAction.SeekOrder(199),
            action(loadedIndex = 0, repeatMode = RepeatMode.All, currentOrderIndex = 0, orderSize = 200),
        )
    }

    /**
     * The divergence from native media3: a large queue under Repeat All whose loaded window has been walked back
     * to its rear edge. Loaded index 0 is order index 137, not the queue start, so native media3
     * wraps within the window (a ~WINDOW_FORWARD jump forward) while the app restarts.
     */
    @Test
    fun `repeat all at the window's rear edge away from the queue start restarts instead of wrapping`() {
        assertEquals(
            PreviousAction.RestartCurrent,
            action(loadedIndex = 0, repeatMode = RepeatMode.All, currentOrderIndex = 137, orderSize = 200),
        )
    }

    @Test
    fun `repeat off and repeat one at the queue start restart`() {
        for (mode in listOf(RepeatMode.Off, RepeatMode.One)) {
            assertEquals(
                PreviousAction.RestartCurrent,
                action(loadedIndex = 0, repeatMode = mode, currentOrderIndex = 0, orderSize = 200),
                "mode=$mode",
            )
        }
    }

    @Test
    fun `a single-song queue under repeat all restarts rather than seeking to itself`() {
        assertEquals(
            PreviousAction.RestartCurrent,
            action(loadedIndex = 0, repeatMode = RepeatMode.All, currentOrderIndex = 0, orderSize = 1),
        )
    }

    @Test
    fun `an unset loaded index falls through to the rear-edge rules`() {
        assertEquals(
            PreviousAction.RestartCurrent,
            action(loadedIndex = C.INDEX_UNSET, currentOrderIndex = 5, orderSize = 10),
        )
        assertEquals(
            PreviousAction.SeekOrder(9),
            action(
                loadedIndex = C.INDEX_UNSET,
                repeatMode = RepeatMode.All,
                currentOrderIndex = 0,
                orderSize = 10,
            ),
        )
    }

    // --- final-item Next under Repeat One (rule system transport now shares) ---------------

    @Test
    fun `repeat one on the final item offers a wrap to the beginning`() {
        assertEquals(0, nextIndexInOrder(count = 60, currentIndex = 59, repeatMode = RepeatMode.One))
    }

    // --- navigationCommandEdit -------------------------------------------------------------
    // Player.Commands itself is exercised on-device (SystemNavigationPlayerInstrumentedTest):
    // its backing SparseBooleanArray is not usable in a JVM unit test.

    private val nextCommands = setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    private val previousCommands =
        setOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

    /** Repeat One on the final item: native availability has neither next command. */
    @Test
    fun `adds both next variants when the app has a next item`() {
        val edit = navigationCommandEdit(hasNext = true, hasPrevious = true)
        assertTrue(edit.add.containsAll(nextCommands))
        assertTrue(edit.remove.none { it in nextCommands })
    }

    @Test
    fun `removes both next variants when the app has no next item`() {
        val edit = navigationCommandEdit(hasNext = false, hasPrevious = true)
        assertEquals(nextCommands, edit.remove)
        assertTrue(edit.add.none { it in nextCommands })
    }

    /** The notification sends previous_media_item, which native hides at the window's rear edge. */
    @Test
    fun `adds both previous variants when the app has a previous action`() {
        val edit = navigationCommandEdit(hasNext = false, hasPrevious = true)
        assertEquals(previousCommands, edit.add)
        assertTrue(edit.remove.none { it in previousCommands })
    }

    @Test
    fun `removes both previous variants when the app has no previous action`() {
        val edit = navigationCommandEdit(hasNext = true, hasPrevious = false)
        assertEquals(previousCommands, edit.remove)
    }

    @Test
    fun `only ever touches the four skip commands`() {
        for (next in listOf(true, false)) {
            for (previous in listOf(true, false)) {
                val edit = navigationCommandEdit(next, previous)
                assertEquals(
                    nextCommands + previousCommands,
                    edit.add + edit.remove,
                    "next=$next previous=$previous",
                )
                assertTrue(edit.add.intersect(edit.remove).isEmpty())
            }
        }
    }
}
