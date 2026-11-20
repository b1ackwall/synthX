package com.v1per.synthx.ui.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Queue mode across a configuration change: the saved queue-mode flag and the crossfade progress
 * agree after the state is restored, so queue mode is on screen, the queue button reads "on" and its
 * first tap leaves queue mode.
 */
@RunWith(AndroidJUnit4::class)
class QueueModeRecreationTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val queue = listOf(QueueSlot("a", Song("a", "Title a", "Artist", "Album", 1L, "x/a")))

    @Test fun queueModeRestoredAfterRecreationShowsQueueModeAndTheFirstTapLeavesIt() {
        val restoration = StateRestorationTester(composeRule)
        var state: QueueModeState? = null
        restoration.setContent {
            val s = rememberQueueModeState(rememberCoroutineScope(), queue, queueGeneration = 0L, onShuffleClick = {})
            SideEffect { state = s }
        }
        composeRule.runOnIdle { requireNotNull(state).onQueueClick() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1f, requireNotNull(state).queueProgress.value) }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            val restored = requireNotNull(state)
            assertTrue("the saved flag comes back", restored.isQueueMode)
            assertEquals("queue mode is on screen from the first frame", 1f, restored.queueProgress.value)
        }

        composeRule.runOnIdle { requireNotNull(state).onQueueClick() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val toggled = requireNotNull(state)
            assertFalse("the first tap leaves queue mode", toggled.isQueueMode)
            assertEquals(0f, toggled.queueProgress.value)
        }
    }

    @Test fun playerModeRestoredAfterRecreationStaysInPlayerMode() {
        val restoration = StateRestorationTester(composeRule)
        var state: QueueModeState? = null
        restoration.setContent {
            val s = rememberQueueModeState(rememberCoroutineScope(), queue, queueGeneration = 0L, onShuffleClick = {})
            SideEffect { state = s }
        }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            val restored = requireNotNull(state)
            assertFalse(restored.isQueueMode)
            assertEquals(0f, restored.queueProgress.value)
        }
    }
}
