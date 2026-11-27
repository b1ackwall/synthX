package com.v1per.synthx.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The replace rule of [showSynthXSnackbar]: a new message replaces a showing snackbar that has no action and
 * queues behind one that has an action. No host is composed here, so nothing times out: a snackbar stays until
 * it is replaced or dismissed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowSynthXSnackbarTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val state = SnackbarHostState()

    private val shown: String? get() = state.currentSnackbarData?.visuals?.message

    /**
     * Shows [message] (with [action], if any) and runs until it is showing or queued. In the background scope, so
     * a snackbar still showing when a test ends is cancelled rather than keeping the test open.
     */
    private fun TestScope.show(message: String, action: String? = null): Deferred<SnackbarResult> {
        val duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long
        val result = backgroundScope.async {
            state.showSynthXSnackbar(message, actionLabel = action, duration = duration)
        }
        runCurrent()
        return result
    }

    private fun TestScope.dismissCurrent() {
        state.currentSnackbarData?.dismiss()
        runCurrent()
    }

    @Test fun aPlainMessageReplacesAShowingPlainOne() = runTest {
        val first = show("Added to queue")
        assertEquals("Added to queue", shown)

        val second = show("Playing next")
        assertEquals(SnackbarResult.Dismissed, first.await())
        assertEquals("Playing next", shown)
        assertFalse(second.isCompleted, "the new message is still showing")
    }

    @Test fun aPlainMessageQueuesBehindAShowingActionOne() = runTest {
        val retry = show("File remained", action = "Retry")
        val plain = show("Added to queue")
        assertEquals("File remained", shown, "the action snackbar is not replaced")
        assertFalse(retry.isCompleted)
        assertFalse(plain.isCompleted)

        state.currentSnackbarData?.performAction()
        runCurrent()
        assertEquals(SnackbarResult.ActionPerformed, retry.await())
        assertEquals("Added to queue", shown, "the queued message shows next")
    }

    @Test fun anActionMessageReplacesAShowingPlainOne() = runTest {
        val plain = show("Added to queue")
        val retry = show("File remained", action = "Retry")
        assertEquals(SnackbarResult.Dismissed, plain.await())
        assertEquals("File remained", shown)
        assertEquals("Retry", state.currentSnackbarData?.visuals?.actionLabel)
        assertFalse(retry.isCompleted)
    }

    @Test fun anActionMessageQueuesBehindAShowingActionOne() = runTest {
        val first = show("File remained", action = "Retry")
        val second = show("Cleanup uncertain", action = "Retry")
        assertEquals("File remained", shown)
        assertFalse(first.isCompleted)

        dismissCurrent()
        assertEquals(SnackbarResult.Dismissed, first.await())
        assertEquals("Cleanup uncertain", shown)
        assertFalse(second.isCompleted)
    }

    @Test fun eachOfThreeQuickPlainMessagesReplacesTheOneBefore() = runTest {
        val first = show("Added to queue")
        val second = show("Added to queue")
        val third = show("Added to queue")
        assertEquals(SnackbarResult.Dismissed, first.await())
        assertEquals(SnackbarResult.Dismissed, second.await())
        assertFalse(third.isCompleted, "only the last one is still showing")
        assertEquals("Added to queue", shown)
    }

    @Test fun plainMessagesQueuedBehindAnActionOneStillShowInTurn() = runTest {
        show("File remained", action = "Retry")
        val queuedFirst = show("Added to queue")
        val queuedSecond = show("Playing next")

        dismissCurrent()
        assertEquals("Added to queue", shown, "the rule applies on arrival; queued messages keep their order")
        assertFalse(queuedFirst.isCompleted)

        dismissCurrent()
        assertEquals(SnackbarResult.Dismissed, queuedFirst.await())
        assertEquals("Playing next", shown)
        assertFalse(queuedSecond.isCompleted)
    }

    @Test fun theLastMessageEndsWhenDismissed() = runTest {
        val only = show("Added to queue")
        dismissCurrent()
        assertEquals(SnackbarResult.Dismissed, only.await())
        assertEquals(null, shown)
    }
}
