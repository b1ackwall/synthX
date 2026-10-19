package com.v1per.synthx.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.components.LocalNotificationPermissionBackend
import com.v1per.synthx.ui.components.NotificationPermissionBackend
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.FakePlaybackEngine.Companion.song
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell's second chance at the notification permission: the first time playback runs, the
 * rationale appears once (unless already asked), and Continue fires the system prompt. The real
 * prompt is replaced by a counter and the "asked" flag lives in isolated preferences.
 */
@RunWith(AndroidJUnit4::class)
class NotificationSecondChanceInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val base: Context = shell.context
    private val preferenceName = "second-chance-${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$preferenceName-$name", mode)
    }
    private val preferences = PreferencesManager(isolated)
    private val systemPrompts = AtomicInteger()
    private val backend = NotificationPermissionBackend(
        preferences = preferences,
        isGranted = { false },
        launchRequest = { systemPrompts.incrementAndGet() },
    )

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() {
        try {
            shell.tearDown()
        } finally {
            base.deleteSharedPreferences("$preferenceName-synthx_prefs")
        }
    }

    private val title get() = base.getString(R.string.notification_permission_title)

    private fun startShell(engine: FakePlaybackEngine) = shell.startShell(engine) { content ->
        CompositionLocalProvider(LocalNotificationPermissionBackend provides backend) { content() }
    }

    @Test fun firstPlaybackShowsTheRationaleAndContinueAsksOnce() {
        startShell(FakePlaybackEngine(song("current"), playing = true))
        shell.awaitText(title)

        composeRule.onNodeWithText(base.getString(R.string.action_continue)).performClick()
        composeRule.waitForIdle()

        assertEquals("system prompt launches", 1, systemPrompts.get())
        assertTrue("the asked flag must be recorded", preferences.notificationPermissionAsked)
        assertEquals(0, shell.textCount(title))
    }

    @Test fun nothingIsShownUntilPlaybackStarts() {
        val engine = FakePlaybackEngine(song("current"), playing = false)
        startShell(engine)
        assertEquals("rationale shown before anything played", 0, shell.textCount(title))

        engine.playingState.value = true

        shell.awaitText(title)
        assertEquals(0, systemPrompts.get())
    }

    @Test fun alreadyAskedMeansNoSecondRationale() {
        preferences.markNotificationPermissionAskedDurably()
        startShell(FakePlaybackEngine(song("current"), playing = true))
        composeRule.waitForIdle()
        assertEquals(0, shell.textCount(title))
        assertEquals(0, systemPrompts.get())
    }
}
