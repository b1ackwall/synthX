package com.v1per.synthx.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.theme.SynthXTheme
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P9-T5 / F19. The gate's decision and dialog, with the system prompt replaced by a counter: what
 * matters here is *when* a request is launched, that the deferred action never waits on it, and
 * that the persisted "asked" flag stops a second prompt — including from a fresh gate over the
 * same preferences, i.e. the next launch.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionGateInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val testId = "notification-gate-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$testId-$name", mode)
    }
    private val preferences = PreferencesManager(context)

    private var launches = 0
    private var granted = false

    @After fun tearDown() {
        base.deleteSharedPreferences("$testId-synthx_prefs")
    }

    private fun newGate(sdkInt: Int = Build.VERSION_CODES.TIRAMISU) = NotificationPermissionGate(
        preferences = preferences,
        isGranted = { granted },
        launchRequest = { launches++ },
        sdkInt = sdkInt,
    )

    /** Composes the gate's real dialog exactly as [rememberNotificationPermissionGate] does. */
    private fun show(gate: NotificationPermissionGate) {
        composeRule.setContent {
            SynthXTheme {
                remember { gate }.let { NotificationRationaleDialog(it.rationaleVisible, it::confirm) }
            }
        }
    }

    private fun title() = base.getString(R.string.notification_permission_title)

    /** F19's core: composing the shell-level gate must not itself ask for anything. */
    @Test fun bareCompositionOfTheRealGateNeverPromptsOrRecordsAnAsk() {
        val before = PreferencesManager(base).notificationPermissionAsked
        composeRule.setContent { SynthXTheme { rememberNotificationPermissionGate() } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(title()).assertDoesNotExist()
        assertEquals(before, PreferencesManager(base).notificationPermissionAsked)
    }

    @Test fun bareCompositionOfTheDialogShowsNothingAndLaunchesNothing() {
        val gate = newGate()
        show(gate)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(title()).assertDoesNotExist()
        assertEquals(0, launches)
        assertFalse(preferences.notificationPermissionAsked)
    }

    @Test fun triggerShowsRationaleFirstAndRunsNothingUntilContinue() {
        val gate = newGate()
        show(gate)
        var proceeded = 0
        composeRule.runOnIdle { gate.runAfterRationale { proceeded++ } }

        composeRule.onNodeWithText(title()).assertExists()
        composeRule.onNodeWithText(base.getString(R.string.notification_permission_message)).assertExists()
        assertEquals("nothing may run or prompt before the user continues", 0, proceeded)
        assertEquals(0, launches)
        assertFalse(preferences.notificationPermissionAsked)
    }

    @Test fun continueMarksAskedFiresTheSystemRequestOnceAndProceedsWithoutWaitingOnIt() {
        val gate = newGate()
        show(gate)
        var proceeded = 0
        composeRule.runOnIdle { gate.runAfterRationale { proceeded++ } }
        composeRule.onNodeWithText(base.getString(R.string.action_continue)).performClick()
        composeRule.waitForIdle()

        // The fake request never answers (still pending) and the action ran anyway.
        assertEquals(1, launches)
        assertEquals(1, proceeded)
        assertTrue(preferences.notificationPermissionAsked)
        composeRule.onNodeWithText(title()).assertDoesNotExist()
    }

    @Test fun askedFlagPreventsAnySecondPromptFromEitherTrigger() {
        val gate = newGate()
        show(gate)
        composeRule.runOnIdle { gate.runAfterRationale {} }
        composeRule.onNodeWithText(base.getString(R.string.action_continue)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, launches)

        var second = 0
        composeRule.runOnIdle { gate.runAfterRationale { second++ } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(title()).assertDoesNotExist()
        assertEquals("already asked: the action runs straight through", 1, second)
        assertEquals(1, launches)

        // A brand-new gate over the same preferences — the next launch, or the other trigger point.
        var third = 0
        val relaunched = newGate()
        composeRule.runOnIdle { relaunched.runAfterRationale { third++ } }
        assertFalse(relaunched.rationaleVisible)
        assertEquals(1, third)
        assertEquals(1, launches)
    }

    @Test fun alreadyGrantedRunsStraightThroughAndDoesNotConsumeTheAsk() {
        granted = true
        val gate = newGate()
        var proceeded = 0
        gate.runAfterRationale { proceeded++ }
        assertEquals(1, proceeded)
        assertFalse(gate.rationaleVisible)
        assertEquals(0, launches)
        assertFalse(preferences.notificationPermissionAsked)
    }

    @Test fun belowAndroid13NeverAsks() {
        val gate = newGate(sdkInt = Build.VERSION_CODES.TIRAMISU - 1)
        var proceeded = 0
        gate.runAfterRationale { proceeded++ }
        assertEquals(1, proceeded)
        assertFalse(gate.rationaleVisible)
        assertEquals(0, launches)
        assertFalse(preferences.notificationPermissionAsked)
    }

    @Test fun aSecondTriggerWhileTheRationaleIsShowingIsIgnoredNotQueued() {
        val gate = newGate()
        show(gate)
        var first = 0
        var second = 0
        composeRule.runOnIdle {
            gate.runAfterRationale { first++ }
            gate.runAfterRationale { second++ }
        }
        composeRule.onNodeWithText(base.getString(R.string.action_continue)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals(1, launches)
    }
}
