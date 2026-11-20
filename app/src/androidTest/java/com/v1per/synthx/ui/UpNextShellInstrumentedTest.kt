package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.FakePlaybackEngine.Companion.song
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Up Next in the real shell over the real view model and a fake engine:
 *  - a new queue generation scrolls Up Next to the top; an edit without one keeps the place;
 *  - after a recreation, queue mode is on screen at once, keeps its place and its first queue-button
 *    tap leaves it;
 *  - accepted behaviour, pinned: controls hidden by an upward drag stay hidden across a collapse and
 *    a re-expand, and Back with the sheet open in queue mode collapses the sheet (queue mode stays).
 */
@RunWith(AndroidJUnit4::class)
class UpNextShellInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val engine = FakePlaybackEngine(song("current"), upcoming = (1..100).map { song("next-$it") })

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun sheetTop(): Float =
        composeRule.onNodeWithTag(PerfTestTags.PlayerSheet).fetchSemanticsNode().boundsInRoot.top

    private fun rootHeight(): Float = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height

    private fun isOpen(): Boolean {
        composeRule.waitForIdle()
        return sheetTop() < rootHeight() * 0.1f
    }

    private fun isClosed(): Boolean {
        composeRule.waitForIdle()
        return sheetTop() > rootHeight() * 0.6f
    }

    private fun TouchInjectionScope.slowDrag(from: Offset, dy: Float) {
        down(from)
        repeat(120) {
            advanceEventTime(16)
            moveBy(Offset(0f, dy / 120))
        }
        repeat(20) {
            advanceEventTime(16)
            moveBy(Offset.Zero)
        }
        up()
    }

    private fun openSheet() {
        val mini = composeRule.onNodeWithTag(PerfTestTags.MiniPlayer).fetchSemanticsNode().boundsInRoot.center
        val travel = sheetTop()
        composeRule.onRoot().performTouchInput { slowDrag(mini, -travel * 0.7f) }
        check(isOpen()) { "the player sheet did not open" }
    }

    private fun enterQueueMode() {
        composeRule.onNodeWithTag(PerfTestTags.QueueButton).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagDisplayed(PerfTestTags.QueueList) }
        composeRule.waitForIdle()
    }

    private fun hasRow(title: String): Boolean =
        composeRule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()

    private fun scrollUpNextTo(index: Int) {
        composeRule.onNodeWithTag(PerfTestTags.QueueList).performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    // --- queue generation -----------------------------------------------------------------------

    @Test fun aNewGenerationScrollsUpNextToTheTop() {
        shell.startShell(engine)
        openSheet()
        enterQueueMode()
        scrollUpNextTo(60)
        assertFalse(hasRow("next-1"))

        composeRule.runOnUiThread {
            engine.upcomingState.value = (1..100).map { QueueSlot("fresh-$it", song("fresh-$it")) }
            engine.queueGenerationState.value++
        }
        composeRule.waitForIdle()

        assertTrue("the new queue opens at its first row", hasRow("fresh-1"))
    }

    @Test fun anEditWithoutANewGenerationKeepsThePlace() {
        shell.startShell(engine)
        openSheet()
        enterQueueMode()
        scrollUpNextTo(60)
        assertTrue(hasRow("next-61"))

        composeRule.runOnUiThread {
            engine.upcomingState.value = listOf(QueueSlot("slot-x", song("x"))) + engine.upcomingState.value
        }
        composeRule.waitForIdle()

        assertTrue("still at the same row", hasRow("next-61"))
        assertFalse(hasRow("next-1"))
    }

    // --- recreation -----------------------------------------------------------------------------

    @Test fun afterARecreationQueueModeIsOnScreenKeepsItsPlaceAndTheFirstTapLeavesIt() {
        shell.installPlayback(engine)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SynthXTheme { MainScaffold(rememberNavController(), startDestination = Screen.Home.route) }
        }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagCount(ShellTestTags.NavHost) > 0 }
        openSheet()
        enterQueueMode()
        scrollUpNextTo(30)
        assertTrue(hasRow("next-31"))

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertTrue(
            "Up Next is composed in queue mode right after the recreation",
            shell.tagCount(PerfTestTags.QueueList) > 0,
        )
        assertTrue("no jump: the place is kept", hasRow("next-31"))
        assertFalse(hasRow("next-1"))

        openSheet()
        assertTrue("queue mode on screen", shell.tagDisplayed(PerfTestTags.QueueList))
        composeRule.onNodeWithTag(PerfTestTags.QueueButton).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagCount(PerfTestTags.QueueList) == 0 }
        assertEquals("the first tap leaves queue mode", 0, shell.tagCount(PerfTestTags.QueueList))
    }

    // --- accepted behaviour, pinned -------------------------------------------------------------

    @Test fun controlsHiddenByAnUpwardDragStayHiddenAcrossACollapseAndAReExpand() {
        shell.startShell(engine)
        openSheet()
        enterQueueMode()

        val list = composeRule.onNodeWithTag(PerfTestTags.QueueList).fetchSemanticsNode().boundsInRoot
        val from = Offset(list.center.x, list.top + 150f)
        composeRule.onRoot().performTouchInput { slowDrag(from, -120f) }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagCount(PerfTestTags.PlayPause) == 0 }

        // Collapse with a drag on the header, above the list.
        val header = Offset(list.center.x, list.top * 0.3f)
        composeRule.onRoot().performTouchInput { slowDrag(header, rootHeight() * 0.7f) }
        assertTrue("collapsed", isClosed())

        openSheet()
        assertTrue("queue mode", shell.tagDisplayed(PerfTestTags.QueueList))
        assertEquals("the controls are still hidden", 0, shell.tagCount(PerfTestTags.PlayPause))
        assertEquals(0, shell.tagCount(PerfTestTags.QueueButton))
    }

    @Test fun backWithTheSheetOpenInQueueModeCollapsesTheSheetAndKeepsQueueMode() {
        shell.startShell(engine)
        openSheet()
        enterQueueMode()

        shell.pressBack()

        assertTrue("Back collapses the sheet", isClosed())
        openSheet()
        assertTrue("queue mode is still on", shell.tagDisplayed(PerfTestTags.QueueList))
    }
}
