package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertPixelEventually
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The app's snackbar as drawn and handled: a light TextPrimary box with no border inside a 12dp margin, a
 * Background message and an underlined Background action. An action tap reaches the caller as ActionPerformed;
 * a swipe either way, or the accessibility Dismiss action, reaches it as Dismissed long before the timeout.
 * The box blocks taps inside its bounds only.
 */
@RunWith(AndroidJUnit4::class)
class SynthXSnackbarInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val state = SnackbarHostState()
    private val results = ConcurrentHashMap<String, SnackbarResult>()
    private val backdropTaps = AtomicInteger()

    private class Message(val text: String, val action: String? = null, val duration: SnackbarDuration)

    /**
     * Shows [messages] in order through showSynthXSnackbar, over a tappable Background backdrop drawn as a sibling
     * beneath the host (as the app's screens are), through [SynthXSnackbarHost] or a tagged [SynthXSnackbar].
     */
    private fun show(vararg messages: Message, tagged: Boolean = false) {
        composeRule.setContent {
            SynthXTheme {
                LaunchedEffect(state) {
                    messages.forEach { message ->
                        launch {
                            results[message.text] =
                                state.showSynthXSnackbar(message.text, message.action, message.duration)
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Background)
                            .pointerInput(Unit) { detectTapGestures { backdropTaps.incrementAndGet() } },
                    )
                    if (tagged) {
                        SnackbarHost(hostState = state, modifier = Modifier.align(Alignment.BottomCenter)) {
                            SynthXSnackbar(it, modifier = Modifier.testTag(SNACKBAR))
                        }
                    } else {
                        SynthXSnackbarHost(hostState = state, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        awaitText(messages.first().text)
    }

    private fun awaitText(text: String) =
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun styleOf(text: String): TextStyle {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style
    }

    private fun currentMessage(): String? = composeRule.runOnUiThread { state.currentSnackbarData?.visuals?.message }

    /** With the clock paused, advances it frame by frame until [done] or [limitMs]; returns the time advanced. */
    private fun advanceUntil(limitMs: Long, done: () -> Boolean): Long {
        val start = composeRule.mainClock.currentTime
        while (!done() && composeRule.mainClock.currentTime - start < limitMs) {
            composeRule.mainClock.advanceTimeByFrame()
        }
        return composeRule.mainClock.currentTime - start
    }

    /** A Short snackbar swiped by [swipe] is Dismissed and gone well before its 4 s timeout. */
    private fun assertSwipeDismissesBeforeTheTimeout(swipe: TouchInjectionScope.() -> Unit) {
        show(Message(PLAIN, duration = SnackbarDuration.Short))
        composeRule.mainClock.autoAdvance = false
        try {
            val start = composeRule.mainClock.currentTime
            composeRule.onNodeWithText(PLAIN).performTouchInput(swipe)
            advanceUntil(SWIPE_LIMIT_MS) { results[PLAIN] != null && currentMessage() == null }
            val elapsed = composeRule.mainClock.currentTime - start
            assertEquals(SnackbarResult.Dismissed, results[PLAIN])
            assertNull("the snackbar is still current", currentMessage())
            assertTrue("dismissed after $elapsed ms, not well before the 4000 ms timeout", elapsed < SWIPE_LIMIT_MS)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test fun theBoxIsTextPrimaryWithNoBorderInsideA12dpMargin() {
        show(Message(MESSAGE, ACTION, SnackbarDuration.Indefinite), tagged = true)
        val margin = with(composeRule.density) { MARGIN.toPx() }
        // Just inside the box, where the old 1dp border was drawn; well inside it; and far out in the margin.
        val edgeX = ceil(margin).toInt() + 1
        val insideX = with(composeRule.density) { (MARGIN + 8.dp).roundToPx() }
        val capture = { composeRule.onNodeWithTag(SNACKBAR).captureToImage() }
        composeRule.assertPixelEventually("container", TextPrimary, capture) { IntOffset(insideX, it.height / 2) }
        composeRule.assertPixelEventually("no border", TextPrimary, capture) { IntOffset(edgeX, it.height / 2) }
        composeRule.assertPixelEventually("margin", Background, capture) { IntOffset(1, it.height / 2) }
    }

    @Test fun messageAndActionAreBackgroundAndNeverAccent() {
        show(Message(MESSAGE, ACTION, SnackbarDuration.Indefinite))
        listOf(MESSAGE, ACTION).forEach { text ->
            val color = styleOf(text).color
            assertNotEquals("'$text' paints Accent", Accent, color)
            assertEquals("'$text' colour", Background, color)
        }
        val action = styleOf(ACTION)
        assertEquals("the action is underlined", TextDecoration.Underline, action.textDecoration)
        assertEquals("the action is medium weight", FontWeight.Medium, action.fontWeight)
    }

    @Test fun aTapOnTheActionReachesTheCallerAsActionPerformed() {
        show(Message(MESSAGE, ACTION, SnackbarDuration.Indefinite))
        composeRule.onNodeWithText(ACTION).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { results[MESSAGE] != null }
        assertEquals(SnackbarResult.ActionPerformed, results[MESSAGE])
    }

    @Test fun aSwipeLeftDismissesWellBeforeTheTimeout() = assertSwipeDismissesBeforeTheTimeout { swipeLeft() }

    @Test fun aSwipeRightDismissesWellBeforeTheTimeout() = assertSwipeDismissesBeforeTheTimeout { swipeRight() }

    @Test fun aShortSlowSwipeSettlesBackWithoutDismissing() {
        show(Message(PLAIN, duration = SnackbarDuration.Indefinite))
        val restingLeft = composeRule.onNodeWithText(PLAIN).getBoundsInRoot().left
        val step = with(composeRule.density) { SHORT_SWIPE_STEP.toPx() }
        // 24dp in about one second: under both the 56dp positional and the 125dp/s fling threshold.
        composeRule.onNodeWithText(PLAIN).performTouchInput {
            down(center)
            repeat(SHORT_SWIPE_STEPS) { moveBy(Offset(-step, 0f), delayMillis = 100) }
            up()
        }
        composeRule.waitForIdle()
        assertNull("a short swipe dismissed it", results[PLAIN])
        assertEquals(PLAIN, currentMessage())
        val settledLeft = composeRule.onNodeWithText(PLAIN).getBoundsInRoot().left
        assertTrue("settled at $settledLeft, not back at $restingLeft", abs((settledLeft - restingLeft).value) < 1f)
    }

    @Test fun theAccessibilityDismissActionStillDismisses() {
        show(Message(MESSAGE, ACTION, SnackbarDuration.Indefinite))
        val pane = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        val config = pane.fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
        assertTrue("the pane has no title", SemanticsProperties.PaneTitle in config)
        pane.performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.waitUntil(TIMEOUT_MS) { results[MESSAGE] != null }
        assertEquals(SnackbarResult.Dismissed, results[MESSAGE])
    }

    @Test fun afterASwipeTheNextQueuedMessageAppears() {
        // The Retry message has an action, so the plain one queues behind it instead of replacing it.
        show(
            Message(MESSAGE, ACTION, SnackbarDuration.Indefinite),
            Message(PLAIN, duration = SnackbarDuration.Indefinite),
        )
        assertEquals(MESSAGE, currentMessage())
        composeRule.onNodeWithText(MESSAGE).performTouchInput { swipeLeft() }
        awaitText(PLAIN)
        assertEquals(SnackbarResult.Dismissed, results[MESSAGE])
        assertEquals(PLAIN, currentMessage())
    }

    @Test fun theBoxBlocksTapsInsideItsBoundsOnly() {
        show(Message(PLAIN, duration = SnackbarDuration.Indefinite), tagged = true)
        composeRule.onNodeWithTag(SNACKBAR).performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertEquals("a tap on the box reached the screen under it", 0, backdropTaps.get())
        composeRule.onNodeWithTag(SNACKBAR).performTouchInput { click(Offset(1f, centerY)) }
        composeRule.waitForIdle()
        assertEquals("a tap in the margin did not reach the screen under it", 1, backdropTaps.get())
        assertFalse("a tap dismissed the snackbar", results.containsKey(PLAIN))
    }

    private companion object {
        const val SNACKBAR = "snackbar"
        const val MESSAGE = "Song deleted, but its file is still on the device"
        const val ACTION = "Retry"
        const val PLAIN = "Added to queue"
        const val TIMEOUT_MS = 5_000L

        /** Half the Short timeout: a swipe dismissal must land well inside it. */
        const val SWIPE_LIMIT_MS = 2_000L
        const val SHORT_SWIPE_STEPS = 10
        val SHORT_SWIPE_STEP = 2.4.dp
        val MARGIN = 12.dp
    }
}
