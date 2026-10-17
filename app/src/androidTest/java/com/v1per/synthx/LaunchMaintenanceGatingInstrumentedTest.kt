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
 * P7-T3 / R7-F03: direct test of [shouldRunLaunchMaintenance] — the real, single condition
 * [MainActivity.runLaunchMaintenance] checks before doing any of its backup/scan/queue-restore/
 * art-sweep work (see that function's kdoc in MainActivity.kt). MainActivity itself is not
 * launched here — no harness in this codebase does (see
 * [com.v1per.synthx.ui.screens.onboarding.OnboardingRecoveryInstrumentedTest]'s own note on
 * this) — but that single early return is a structural guarantee, confirmed by reading
 * MainActivity.kt: every downstream call in `runLaunchMaintenance` is unreachable once this
 * returns `false`, which is what makes testing the condition itself a faithful proxy for "no
 * backup taken, no scan enqueued."
 */
@RunWith(AndroidJUnit4::class)
class LaunchMaintenanceGatingInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

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
