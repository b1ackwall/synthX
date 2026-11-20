package com.v1per.synthx.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The Up Next list's geometry in the sheet's real numbers: the list starts 180dp down, the
 * controls cover the bottom 300dp, and the sheet clips at [SHEET]. The list is as tall as the strip
 * between its top and the sheet's bottom edge. Its end padding follows the controls, so the last row
 * can come up to the controls' top while they show and reaches the sheet's bottom edge while they
 * are hidden. The reorder auto-scroll's forward zone sits on screen above the controls (or above the
 * sheet's bottom edge while they are hidden). The list's on-screen bounds and the mask over the
 * controls stay where they are.
 */
@RunWith(AndroidJUnit4::class)
class QueueUpNextGeometryTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val overlayVisibleAlpha = mutableFloatStateOf(1f)
    private val moves = mutableListOf<Pair<String, Int>>()
    private lateinit var listState: LazyListState

    private fun song(id: String) = Song(id, "Title $id", "Artist", "Album", 1000L, "audio/$id")

    private var queue = (0 until ROWS).map { QueueSlot("s$it", song("s$it")) }

    private fun setUp(controlsVisible: Boolean) {
        overlayVisibleAlpha.floatValue = if (controlsVisible) 1f else 0f
        composeRule.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                Box(Modifier.fillMaxWidth().height(SHEET).clipToBounds().testTag(HOST)) {
                    listState = rememberLazyListState()
                    PlayerSkeletonQueueContent(
                        song = song("current"),
                        artOffsetYQueue = 0.dp,
                        queueListOffsetY = LIST_TOP,
                        headerRegionHeight = LIST_TOP,
                        queueListState = listState,
                        queueScrollConnection = object : NestedScrollConnection {},
                        expandedContentAlpha = 1f,
                        queueProgressValue = 1f,
                        expandedContentEnabled = true,
                        queueModeArtSize = 48.dp,
                        upcomingQueue = queue,
                        shuffleEnabled = false,
                        repeatMode = RepeatMode.Off,
                        onShuffleClick = {},
                        onRepeatClick = {},
                        onQueueSongClick = {},
                        onQueueMove = { slotId, toIndex -> moves += slotId to toIndex },
                        onQueueRemove = {},
                        controlsOverlayHeight = OVERLAY,
                        queueFadeAlpha = 1f,
                        controlsOverlayAlpha = overlayVisibleAlpha.floatValue,
                        overlayVisibleAlpha = overlayVisibleAlpha.floatValue,
                        controlsOverlaySlide = 0.dp,
                        bottomInset = 0.dp,
                        fullHeight = SHEET,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun px(dp: Dp): Float = with(composeRule.density) { dp.toPx() }

    private fun hostTop(): Float = composeRule.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot.top

    private fun row(id: String) =
        composeRule.onNode(hasTestTag(PerfTestTags.QueueRow) and hasAnyDescendant(hasText("Title $id")))

    private fun rowBottom(id: String): Float = with(composeRule.density) {
        row(id).getUnclippedBoundsInRoot().bottom.toPx()
    }

    private fun scrollToEnd() {
        composeRule.onNodeWithTag(PerfTestTags.QueueList).performScrollToIndex(ROWS - 1)
        composeRule.waitForIdle()
    }

    private fun assertNear(message: String, expected: Float, actual: Float) =
        assertTrue("$message: expected $expected px, was $actual px", abs(expected - actual) <= px(1.dp))

    @Test fun withTheControlsShowingTheLastRowEndsAtTheControlsTop() {
        setUp(controlsVisible = true)
        scrollToEnd()

        assertNear("last row bottom", hostTop() + px(SHEET - OVERLAY), rowBottom("s${ROWS - 1}"))
    }

    @Test fun withTheControlsHiddenTheLastRowReachesTheSheetsBottomEdge() {
        setUp(controlsVisible = false)
        scrollToEnd()

        assertNear("last row bottom, no gap", hostTop() + px(SHEET), rowBottom("s${ROWS - 1}"))
    }

    @Test fun hidingTheControlsAtTheEndClosesTheGapAndShowingThemKeepsThePlace() {
        setUp(controlsVisible = true)
        scrollToEnd()

        composeRule.runOnIdle { overlayVisibleAlpha.floatValue = 0f }
        composeRule.waitForIdle()
        assertNear("hidden", hostTop() + px(SHEET), rowBottom("s${ROWS - 1}"))

        composeRule.runOnIdle { overlayVisibleAlpha.floatValue = 1f }
        composeRule.waitForIdle()
        assertNear(
            "shown again: the rows stay, the controls cover the end",
            hostTop() + px(SHEET),
            rowBottom("s${ROWS - 1}"),
        )
    }

    @Test fun theListsOnScreenBoundsStayTheVisibleStrip() {
        setUp(controlsVisible = true)

        val list = composeRule.onNodeWithTag(PerfTestTags.QueueList).fetchSemanticsNode().boundsInRoot
        assertNear("list top", hostTop() + px(LIST_TOP), list.top)
        assertNear("list bottom on screen", hostTop() + px(SHEET), list.bottom)
    }

    @Test fun theMaskHidesRowsUnderTheControlsAndLeavesTheStripAboveIt() {
        setUp(controlsVisible = true)
        val image = composeRule.onNodeWithTag(HOST).captureToImage().asAndroidBitmap()
        val background = image[image.width - 1, 1]

        val underControls = drawnRows(image, background, px(SHEET - OVERLAY) + 1f, px(SHEET) - 1f)
        val aboveFade = drawnRows(image, background, px(LIST_TOP), px(SHEET - OVERLAY - FADE) - 1f)

        assertEquals("nothing drawn under the controls", 0, underControls)
        assertTrue("rows drawn in the strip above the fade", aboveFade > 0)
    }

    @Test fun aHandleHeldJustAboveTheControlsAutoScrollsForward() {
        setUp(controlsVisible = true)
        assertForwardAutoScroll(zoneBottom = hostTop() + px(SHEET - OVERLAY))
    }

    @Test fun withTheControlsHiddenTheForwardZoneIsAboveTheSheetsBottomEdge() {
        setUp(controlsVisible = false)
        assertForwardAutoScroll(zoneBottom = hostTop() + px(SHEET))
    }

    /**
     * The reorder state is rebuilt when its threshold padding changes, and a rebuild ends the drag in
     * progress: the row would drop where it is at that moment and the rest of the drag would be lost.
     * The toggle comes 70dp into the drag (a drop there lands at index 1 at most); the full drag goes
     * 270dp further, past the forward zone, so the row ends at index 3 or later.
     */
    @Test fun theControlsTogglingMidDragDoesNotDropTheDrag() {
        setUp(controlsVisible = true)
        val handle = handleCenter("s0")

        composeRule.onRoot().performTouchInput {
            down(handle)
            repeat(DRAG_STEPS) {
                advanceEventTime(16)
                moveBy(Offset(0f, px(70.dp) / DRAG_STEPS))
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { overlayVisibleAlpha.floatValue = 0f }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            repeat(DRAG_STEPS) {
                advanceEventTime(16)
                moveBy(Offset(0f, px(200.dp) / DRAG_STEPS))
            }
            up()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals("the drag kept its row and reached the engine once", listOf("s0"), moves.map { it.first })
            assertTrue("dropped at the end of the whole drag, $moves", moves.single().second >= 3)
        }
    }

    @Test fun aOneRowUpNextStaysAtTheTopWithTheControlsShownOrHidden() {
        queue = queue.take(1)
        setUp(controlsVisible = true)
        val top = row("s0").getUnclippedBoundsInRoot().top

        composeRule.runOnIdle { overlayVisibleAlpha.floatValue = 0f }
        composeRule.waitForIdle()

        assertEquals(top, row("s0").getUnclippedBoundsInRoot().top)
        assertNear("the row sits at the list top", hostTop() + px(LIST_TOP), with(composeRule.density) { top.toPx() })
    }

    // --- helpers --------------------------------------------------------------------------------

    /** The centre of [id]'s drag handle: the "Reorder" button inside that row's bounds. */
    private fun handleCenter(id: String): Offset {
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_drag_handle)
        val rowBounds = row(id).fetchSemanticsNode().boundsInRoot
        return composeRule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes()
            .map { it.boundsInRoot.center }
            .single { it.y in rowBounds.top..rowBounds.bottom }
    }

    /**
     * Drags the last fully visible row's handle down to a point just inside the forward zone (20dp
     * above [zoneBottom]), holds it there, then drops it. The list has to scroll forward under the
     * held row, so the drop lands beyond the last row that was visible when the drag began.
     */
    private fun assertForwardAutoScroll(zoneBottom: Float) {
        val listTop = hostTop() + px(LIST_TOP)
        val lastVisible = composeRule.runOnIdle {
            listState.layoutInfo.visibleItemsInfo.last { item ->
                listTop + item.offset + item.size <= zoneBottom + 1f
            }.index
        }
        val start = handleCenter("s$lastVisible")
        val target = zoneBottom - px(20.dp)
        composeRule.onRoot().performTouchInput {
            down(start)
            repeat(DRAG_STEPS) {
                advanceEventTime(16)
                moveBy(Offset(0f, (target - start.y) / DRAG_STEPS))
            }
        }
        repeat(HOLD_FRAMES) {
            composeRule.onRoot().performTouchInput {
                advanceEventTime(16)
                moveBy(Offset(0f, if (it % 2 == 0) 0.5f else -0.5f))
            }
            composeRule.mainClock.advanceTimeBy(16)
        }
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue("the list scrolled forward under the held row", listState.firstVisibleItemIndex > 0)
            val move = moves.singleOrNull()
            assertTrue("one move reached the engine: $moves", move != null && move.first == "s$lastVisible")
            assertTrue(
                "dropped past the last row visible at the start ($lastVisible): $moves",
                move!!.second > lastVisible,
            )
        }
    }

    /** Pixel rows between [fromPx] and [toPx] (host coordinates) that hold anything but [background]. */
    private fun drawnRows(image: Bitmap, background: Int, fromPx: Float, toPx: Float): Int =
        (fromPx.toInt().coerceAtLeast(0)..toPx.toInt().coerceAtMost(image.height - 1)).count { y ->
            (0 until image.width).any { x -> image[x, y] != background }
        }

    private companion object {
        const val ROWS = 40
        const val DRAG_STEPS = 20
        const val HOLD_FRAMES = 90
        const val HOST = "geometry_sheet"
        val SHEET = 640.dp
        val LIST_TOP = 180.dp
        val OVERLAY = 300.dp
        val FADE = 48.dp
    }
}
