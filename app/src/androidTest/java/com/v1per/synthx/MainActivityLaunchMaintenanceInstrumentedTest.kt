package com.v1per.synthx

import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.lifecycle.Lifecycle
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.data.scanner.IssuesScanWorker
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.testing.DebugPreferencesSnapshot
import com.v1per.synthx.testing.MainActivityLauncher
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch maintenance as the real [MainActivity] runs it: only once onboarding is complete
 * and no first scan is pending. Observed through its side effects: the art sweep flag it
 * consumes, the Issues scan it enqueues when due, the launch scans it enqueues with a music
 * folder. Also the two lifecycle hooks: leaving the app saves the play position, returning
 * re-syncs playback, both only once the database gate is open and no restore has ended the
 * session. Runs against the real debug preferences, saved and put back by
 * [DebugPreferencesSnapshot].
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchMaintenanceInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule(order = 1)
    val preferencesSnapshot = DebugPreferencesSnapshot()

    @get:Rule(order = 2)
    val composeRule = createEmptyComposeRule()

    private val launcher = MainActivityLauncher(composeRule)
    private val context = launcher.context
    private val preferences = PreferencesManager(context)
    private val workManager get() = WorkManager.getInstance(context)

    @Before fun setUp() {
        launcher.setUp()
        workManager.cancelUniqueWork(IssuesScanWorker.UNIQUE_WORK_NAME).result.get()
        workManager.pruneWork().result.get()
        // Not due, so maintenance skips the backup and reaches the art sweep at once.
        preferences.lastDailyBackupTimestamp = System.currentTimeMillis()
    }

    @After fun tearDown() {
        try {
            launcher.tearDown()
        } finally {
            workManager.cancelUniqueWork(IssuesScanWorker.UNIQUE_WORK_NAME).result.get()
            workManager.pruneWork().result.get()
        }
    }

    private fun issuesScanEnqueued(): Boolean =
        workManager.getWorkInfosForUniqueWork(IssuesScanWorker.UNIQUE_WORK_NAME).get().isNotEmpty()

    @Test fun maintenanceRunsOnceOnboardingIsComplete() {
        check(preferences.setOnboardingCompleteDurably(true))
        preferences.artSweepPending = true
        preferences.lastIssuesScanTimestamp = 0L
        launcher.launchToShell()
        composeRule.waitUntil(MainActivityLauncher.WAIT_MS) { !preferences.artSweepPending }
        assertTrue("an overdue Issues scan was not enqueued", issuesScanEnqueued())
    }

    // Fixed 3 s negative window: no signal says "maintenance skipped"; the control above finishes in about 1.5 s.
    @Test(timeout = 30_000L)
    fun maintenanceIsHeldBackDuringTheWizard() {
        preferences.artSweepPending = true
        preferences.lastIssuesScanTimestamp = 0L
        launcher.launchToShell()
        assertTrue(launcher.textCount(context.getString(R.string.onboarding_welcome_title)) > 0)

        SystemClock.sleep(NEGATIVE_WINDOW_MS)

        assertTrue("launch maintenance consumed the art sweep flag during the wizard", preferences.artSweepPending)
        assertEquals("launch maintenance enqueued an Issues scan during the wizard", false, issuesScanEnqueued())
    }

    @Test fun anIssuesScanThatIsNotDueIsNotEnqueued() {
        check(preferences.setOnboardingCompleteDurably(true))
        preferences.artSweepPending = true
        preferences.lastIssuesScanTimestamp = System.currentTimeMillis()
        launcher.launchToShell()
        // The art sweep runs after the Issues check in the same sequence, so this is a clean "after".
        composeRule.waitUntil(MainActivityLauncher.WAIT_MS) { !preferences.artSweepPending }
        assertEquals("an Issues scan run less than a day ago was enqueued again", false, issuesScanEnqueued())
    }

    @Test fun launchScansAreEnqueuedWhenAMusicFolderIsSet() {
        check(preferences.setOnboardingCompleteDurably(true))
        preferences.lastIssuesScanTimestamp = System.currentTimeMillis()
        preferences.musicFolderUri = "content://com.v1per.synthx.maintenance/tree/music"
        ScanStubWorkerFactory.behavior = ScanStub.Hang
        try {
            launcher.launchToShell()
            composeRule.waitUntil(MainActivityLauncher.WAIT_MS) {
                workManager.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).get().isNotEmpty()
            }
        } finally {
            workManager.cancelUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).result.get()
            ScanStubWorkerFactory.behavior = null
        }
    }

    // ------------------------------------------------------------ leaving and returning (onStop / onStart)

    private fun launchSeededBehindAHeldGate(engine: FakePlaybackEngine): MainActivityLauncher.HeldGate {
        check(preferences.setOnboardingCompleteDurably(true))
        val gate = launcher.holdGate()
        launcher.launch()
        launcher.seedPlayback(engine)
        return gate
    }

    @Test fun leavingTheAppSavesThePlayPosition() {
        val engine = FakePlaybackEngine()
        val gate = launchSeededBehindAHeldGate(engine)
        gate.release()
        launcher.awaitShell()

        launcher.scenarioOrFail().moveToState(Lifecycle.State.CREATED)

        assertEquals("onStop must save the play position once", 1, engine.persistCalls.get())
    }

    @Test fun leavingBeforeTheDatabaseGateOpensSavesNothing() {
        val engine = FakePlaybackEngine()
        val gate = launchSeededBehindAHeldGate(engine)
        try {
            launcher.scenarioOrFail().moveToState(Lifecycle.State.CREATED)
            assertEquals("saved playback state before the database was ready", 0, engine.persistCalls.get())
        } finally {
            gate.release()
        }
    }

    @Test fun leavingDuringARestoreTerminalStateSavesNothing() {
        val engine = FakePlaybackEngine()
        launchSeededBehindAHeldGate(engine).release()
        launcher.awaitShell()
        // A restore that closed Room, as RestoreTransaction does it.
        runBlocking {
            RestoreCoordinator.exclusive { }
            RestoreCoordinator.quiesceReactiveOperations()
        }
        RestoreCoordinator.markClosed()

        launcher.scenarioOrFail().moveToState(Lifecycle.State.CREATED)

        assertEquals("saved playback state after a restore terminal state", 0, engine.persistCalls.get())
    }

    @Test fun startupResyncsPlaybackOnlyAfterTheGateOpens() {
        val engine = FakePlaybackEngine()
        val gate = launchSeededBehindAHeldGate(engine)
        try {
            assertEquals("re-synced before the database gate opened", 0, engine.reconcileCalls.get())
        } finally {
            gate.release()
        }
        composeRule.waitUntil(MainActivityLauncher.WAIT_MS) { engine.reconcileCalls.get() >= 1 }
        launcher.awaitShell()
        assertEquals("one re-sync per start", 1, engine.reconcileCalls.get())
    }

    /**
     * PINNED current behaviour, confirmed bug (see slice3-implementation.md): with the gate already
     * open, onStart's check runs before the lifecycle reaches STARTED, so returning to the app
     * never re-syncs playback. The count is final when moveToState returns: nothing suspends.
     */
    @Test fun pinnedReturningToTheAppDoesNotResyncPlayback() {
        val engine = FakePlaybackEngine()
        launchSeededBehindAHeldGate(engine).release()
        composeRule.waitUntil(MainActivityLauncher.WAIT_MS) { engine.reconcileCalls.get() >= 1 }
        launcher.awaitShell()

        launcher.scenarioOrFail().moveToState(Lifecycle.State.CREATED)
        launcher.scenarioOrFail().moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertEquals("a correct return would re-sync a second time", 1, engine.reconcileCalls.get())
    }

    private companion object {
        const val NEGATIVE_WINDOW_MS = 3_000L
    }
}
