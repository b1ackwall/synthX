package com.v1per.synthx

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.MigrationInvariantFailedException
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.testing.DebugPreferencesSnapshot
import com.v1per.synthx.testing.MainActivityLauncher
import com.v1per.synthx.ui.ShellTestTags
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real [MainActivity] and checks which screen a cold start shows: the wizard until
 * onboarding is complete and no first scan is pending, Home after that; nothing until the
 * database gate opens; the failure, recovery or emergency screen when start-up cannot proceed.
 * Runs against the real debug preferences, saved and put back by [DebugPreferencesSnapshot].
 */
@RunWith(AndroidJUnit4::class)
class MainActivityStartupInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule(order = 1)
    val preferencesSnapshot = DebugPreferencesSnapshot()

    @get:Rule(order = 2)
    val composeRule = createEmptyComposeRule()

    private val launcher = MainActivityLauncher(composeRule)
    private val context = launcher.context
    private val preferences = PreferencesManager(context)

    @Before fun setUp() = launcher.setUp()

    @After fun tearDown() = launcher.tearDown()

    private fun welcomeShown() = launcher.textCount(context.getString(R.string.onboarding_welcome_title)) > 0

    // ------------------------------------------------------------ cold-start destination

    @Test fun freshInstallStartsInTheWizard() {
        launcher.launchToShell()
        assertTrue("wizard welcome not shown on a fresh install", welcomeShown())
        assertFalse("Home shown on a fresh install", launcher.homeShown())
    }

    @Test fun completedOnboardingStartsAtHome() {
        check(preferences.setOnboardingCompleteDurably(true))
        launcher.launchToShell()
        assertTrue("Home not shown after onboarding", launcher.homeShown())
        assertFalse(welcomeShown())
    }

    @Test fun pendingFirstScanStillStartsInTheWizard() {
        check(preferences.setOnboardingCompleteDurably(true))
        check(preferences.trackFirstScanRequestDurably(UUID.randomUUID().toString()))
        launcher.launchToShell()
        assertFalse("Home shown while the first scan is pending", launcher.homeShown())
    }

    // ------------------------------------------------------------ which start-up screen

    private fun shellShown() = launcher.tagCount(ShellTestTags.NavHost) > 0

    @Test fun nothingIsShownUntilTheDatabaseGateOpens() {
        check(preferences.setOnboardingCompleteDurably(true))
        val gate = launcher.holdGate()
        try {
            // launch() returns once the activity is resumed; start-up is then waiting on the gate.
            launcher.launch()
            assertEquals("content was set before the database gate opened", 0, launcher.contentViewCount())
            assertEquals("the live database was inspected before the gate opened", 0, gate.attempts.get())
        } finally {
            gate.release()
        }
        launcher.awaitShell()
        assertTrue(launcher.homeShown())
    }

    @Test fun aFailedStartShowsTheFailureScreenAndRetryReachesTheApp() {
        check(preferences.setOnboardingCompleteDurably(true))
        val attempts = launcher.failingGate { IllegalStateException("startup test failure") }
        launcher.launch()
        launcher.awaitText(context.getString(R.string.startup_failure_title))
        assertFalse("the app was composed after a failed start", shellShown())

        composeRule.onAllNodesWithText(context.getString(R.string.action_retry))[0].performClick()

        launcher.awaitShell()
        assertTrue(launcher.homeShown())
        assertEquals("Retry makes exactly one new attempt", 2, attempts.get())
    }

    @Test fun aFailedMigrationCheckShowsTheRecoveryScreenWithoutRetry() {
        check(preferences.setOnboardingCompleteDurably(true))
        launcher.failingGate { MigrationInvariantFailedException("startup test invariant failure") }
        launcher.launch()
        val titles = listOf(R.string.migration_recovery_title, R.string.migration_recovery_unavailable_title)
            .map(context::getString)
        composeRule.waitUntil(MainActivityLauncher.WAIT_MS) { titles.any { launcher.textCount(it) > 0 } }
        assertEquals(0, launcher.textCount(context.getString(R.string.startup_failure_title)))
        assertEquals(0, launcher.textCount(context.getString(R.string.action_retry)))
        assertFalse(shellShown())
    }

    @Test fun anEmergencyRestoreStateShowsTheEmergencyScreen() {
        check(preferences.setOnboardingCompleteDurably(true))
        launcher.failingGate(failures = 0) { error("unused") }
        RestoreCoordinator.markEmergency()
        launcher.launch()
        // RestoreTerminalContent hard-codes this label.
        launcher.awaitText("Export diagnostics")
        assertFalse(shellShown())
    }
}
