package com.v1per.synthx.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #169: [DestructiveActionTracker.resetForTest] must clear the *persisted* count, not just the
 * in-memory StateFlow — [DestructiveActionTracker.recordDestructiveAction] increments the
 * persisted value, so a stale one surfaces as an off-by-N count in the next test's assertion.
 *
 * Instrumented because [PreferencesManager] needs real SharedPreferences. Runs against an
 * isolated preference file so the stale count it seeds never touches the debug app's own.
 */
@RunWith(AndroidJUnit4::class)
class DestructiveActionTrackerInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private lateinit var context: IsolatedPreferencesContext

    @Before fun setUp() {
        context = IsolatedPreferencesContext(ApplicationProvider.getApplicationContext())
        DestructiveActionTracker.resetForTest(context)
    }

    @After fun tearDown() {
        // Before cleanUp(): resetting afterwards would recreate the isolated preference file.
        DestructiveActionTracker.resetForTest(context)
        context.cleanUp()
    }

    @Test fun resetForTestClearsPersistedCountSoTheNextRecordStartsFromOne() {
        // A previous case/run left a persisted count behind, with the in-memory flow agreeing.
        PreferencesManager(context).destructiveActionsSinceBackup = 4
        DestructiveActionTracker.recordDestructiveAction(context)
        assertEquals(5, DestructiveActionTracker.count.value)

        DestructiveActionTracker.resetForTest(context)
        assertEquals(0, DestructiveActionTracker.count.value)
        assertEquals(0, PreferencesManager(context).destructiveActionsSinceBackup)

        DestructiveActionTracker.recordDestructiveAction(context)
        assertEquals(1, DestructiveActionTracker.count.value)
        assertEquals(1, PreferencesManager(context).destructiveActionsSinceBackup)
    }

    @Test fun resetForTestReArmsInitializeToReadThePersistedCount() {
        DestructiveActionTracker.initialize(context)
        PreferencesManager(context).destructiveActionsSinceBackup = 3

        DestructiveActionTracker.resetForTest(context)
        // Reset zeroed the persisted value, then a later write lands before re-initialisation.
        PreferencesManager(context).destructiveActionsSinceBackup = 2
        DestructiveActionTracker.initialize(context)
        assertEquals(2, DestructiveActionTracker.count.value)
    }

    /** Redirects every preference file to a per-instance name, deleted again by [cleanUp]. */
    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val prefix = "tracker-test-${UUID.randomUUID()}"
        private val preferenceNames = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames += isolatedName
            return super.getSharedPreferences(isolatedName, mode)
        }

        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }
}
