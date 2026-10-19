package com.v1per.synthx.ui.screens.replacesong

import androidx.compose.foundation.layout.Box
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplaceSongSuccessInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun warningFreeCompletionShowsAcknowledgementBeforeExit() {
        var exited = false
        composeRule.setContent {
            val host = remember { SnackbarHostState() }
            Box {
                SnackbarHost(hostState = host)
                ReplaceSongSuccessAcknowledgement(
                    complete = ReplaceSongOutcome.Complete(warning = null),
                    snackbarHostState = host,
                    message = "Replacement acknowledged",
                    onExit = { exited = true },
                )
            }
        }

        composeRule.onNodeWithText("Replacement acknowledged").assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(exited) }
        composeRule.waitUntil(timeoutMillis = 8_000) { exited }
        composeRule.runOnIdle { assertTrue(exited) }
    }
}
