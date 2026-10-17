package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.FakePlaybackEngine.Companion.song
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real player sheet in the real shell, driven by touch: drag, fling, release short of or past
 * the settle point, and taps that belong to a child or to the open sheet. Checks only where the
 * sheet settles (open or closed), never how it moves. Distances are fractions of the sheet's own
 * travel, so they do not depend on the screen size.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSheetGestureInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val current = song("current")
    private val engine = FakePlaybackEngine(current, upcoming = (1..30).map { song("next-$it") })

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private enum class Sheet { Open, Closed, Between }

    private fun sheetTop(): Float =
        composeRule.onNodeWithTag(PerfTestTags.PlayerSheet).fetchSemanticsNode().boundsInRoot.top

    private var rootHeight = 0f
    private var rootCenterX = 0f

    /** Where the sheet came to rest once every animation has finished. */
    private fun settled(): Sheet {
        composeRule.waitForIdle()
        val top = sheetTop()
        return when {
            top < rootHeight * 0.1f -> Sheet.Open
            top > rootHeight * 0.6f -> Sheet.Closed
            else -> Sheet.Between
        }
    }

    private fun start(): Float {
        shell.startShell(engine)
        // Measured while the shell is the only window (a dialog adds a second root).
        composeRule.onRoot().fetchSemanticsNode().boundsInRoot.let {
            rootHeight = it.height
            rootCenterX = it.center.x
        }
        assertEquals(Sheet.Closed, settled())
        // The closed sheet's top is (nearly) its whole travel; the open sheet's top is 0.
        return sheetTop()
    }

    private fun miniCenter(): Offset =
        composeRule.onNodeWithTag(PerfTestTags.MiniPlayer).fetchSemanticsNode().boundsInRoot.center

    /** A slow drag by [dy], then a still hold, so the release carries no fling velocity. */
    private fun TouchInjectionScope.slowDrag(from: Offset, dy: Float) {
        down(from)
        val steps = 120
        repeat(steps) {
            advanceEventTime(16)
            moveBy(Offset(0f, dy / steps))
        }
        repeat(20) {
            advanceEventTime(16)
            moveBy(Offset.Zero)
        }
        up()
    }

    /** A short, fast flick by [dy]. */
    private fun TouchInjectionScope.flick(from: Offset, dy: Float) {
        down(from)
        repeat(4) {
            advanceEventTime(8)
            moveBy(Offset(0f, dy / 4))
        }
        up()
    }

    private fun open(travel: Float) {
        val from = miniCenter()
        composeRule.onRoot().performTouchInput { slowDrag(from, -travel * 0.7f) }
        assertEquals("a long drag up opens the sheet", Sheet.Open, settled())
    }

    @Test fun aLongDragUpOpensTheSheetAndALongDragDownClosesIt() {
        val travel = start()
        open(travel)

        val from = Offset(rootCenterX, travel * 0.15f)
        composeRule.onRoot().performTouchInput { slowDrag(from, travel * 0.7f) }

        assertEquals(Sheet.Closed, settled())
    }

    @Test fun aSlowDragPastAFifthOfTheTravelSettlesOpen() {
        val travel = start()
        val from = miniCenter()

        composeRule.onRoot().performTouchInput { slowDrag(from, -travel * 0.35f) }

        assertEquals(Sheet.Open, settled())
    }

    @Test fun aSlowDragShortOfAFifthOfTheTravelSettlesBackClosed() {
        val travel = start()
        val from = miniCenter()

        composeRule.onRoot().performTouchInput { slowDrag(from, -travel * 0.08f) }

        assertEquals(Sheet.Closed, settled())
    }

    @Test fun aShortQuickFlickUpOpensTheSheet() {
        val travel = start()
        val from = miniCenter()

        composeRule.onRoot().performTouchInput { flick(from, -travel * 0.08f) }

        assertEquals(Sheet.Open, settled())
    }

    @Test fun tappingTheMiniPlayPauseResumesWithoutOpeningTheSheet() {
        start()

        composeRule.onNodeWithTag(PerfTestTags.MiniPlayPause).performClick()

        assertEquals(Sheet.Closed, settled())
        assertEquals("the button's own tap reaches the player", 1, engine.resumeCalls.get())
    }

    @Test fun tappingTheOpenSheetKeepsItOpen() {
        val travel = start()
        open(travel)

        // Upper edge of the open sheet, above the art.
        composeRule.onRoot().performTouchInput { click(Offset(rootCenterX, travel * 0.03f)) }

        assertEquals(Sheet.Open, settled())
    }

    @Test fun draggingDownInTheQueueListLeavesTheSheetOpen() {
        val travel = start()
        open(travel)
        composeRule.onNodeWithTag(PerfTestTags.QueueButton).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagDisplayed(PerfTestTags.QueueList) }

        val list = composeRule.onNodeWithTag(PerfTestTags.QueueList).fetchSemanticsNode().boundsInRoot
        val from = Offset(list.center.x, list.top + list.height * 0.2f)
        composeRule.onRoot().performTouchInput { slowDrag(from, list.height * 0.6f) }

        assertEquals("the queue keeps its own drags", Sheet.Open, settled())
    }

    @Test fun draggingDownOnTheQueueControlsLeavesTheSheetOpen() {
        val travel = start()
        open(travel)
        composeRule.onNodeWithTag(PerfTestTags.QueueButton).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagDisplayed(PerfTestTags.QueueList) }

        // The controls band under the list, beside the buttons rather than on one.
        val playPause = composeRule.onNodeWithTag(PerfTestTags.PlayPause).fetchSemanticsNode().boundsInRoot
        val from = Offset(rootCenterX * 0.06f, playPause.center.y)
        composeRule.onRoot().performTouchInput { slowDrag(from, travel * 0.5f) }

        assertEquals("the controls band does not close the queue", Sheet.Open, settled())
    }
}
