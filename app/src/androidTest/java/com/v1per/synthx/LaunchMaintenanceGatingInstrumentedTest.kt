package com.v1per.synthx

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Direct test of [shouldRunLaunchMaintenance], the condition
 * [MainActivity.runLaunchMaintenance] checks before any of its backup/scan/queue-restore/
 * art-sweep work. This tests the rule only, not that MainActivity calls it:
 * [MainActivityLaunchMaintenanceInstrumentedTest] launches the real activity and checks that
 * maintenance is held back during the wizard and runs once onboarding is complete.
 */
@RunWith(AndroidJUnit4::class)
class LaunchMaintenanceGatingInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val testId = "launch-maintenance-gate-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            appContext.getSharedPreferences("$testId-$name", mode)
    }
    private val preferences = PreferencesManager(context)

    @After fun tearDown() {
        appContext.deleteSharedPreferences("$testId-synthx_prefs")
    }

    /** Acceptance test: an interrupted recovery relaunching into the wizard must not race
     *  launch maintenance's backup/scan chain against its just-picked, half-configured folders. */
    @Test fun doesNotRunWhileOnboardingIncomplete() {
        assertFalse(preferences.onboardingComplete)
        assertFalse(shouldRunLaunchMaintenance(preferences))
    }

    @Test fun runsOnceOnboardingIsComplete() {
        check(preferences.setOnboardingCompleteDurably(true))
        assertTrue(shouldRunLaunchMaintenance(preferences))
    }
}
