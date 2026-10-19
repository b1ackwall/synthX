package com.v1per.synthx.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestorePermitLeakCheck
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.RestoreTerminalContent
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the shell-level restore gate: that RESTORE_PREPARING removes the nav graph from the
 * composition entirely rather than drawing over it, that it is reversible on an early abort,
 * and that the pre-existing Terminal handover still happens on top of it.
 *
 * The whole point of these tests is the distinction the gap was made of — "covered" versus
 * "not composed" — so every assertion is about the *absence* of the nav host, never about a
 * scrim being on top of it.
 */
@RunWith(AndroidJUnit4::class)
class RestoreShellInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        // Same order as PhaseBRestoreTestBase.setUp. WorkManager's manifest initializer
        // is removed for androidTest, and the shell reaches it through PlaylistExportManager, so
        // it has to be stood up explicitly or composing NormalShell takes the activity down.
        RestoreStartup.await()
        TestWorkManager.ensureInitialized(context)
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        RestoreStartup.resetForTest()
        DatabaseStartupGate.markReadyForTest()
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    @After
    fun tearDown() {
        try {
            // Cancel anything the shell left collecting through the production quiescence path,
            // then fail if any permit is still held.
            RestorePermitLeakCheck.assertDrainedThenReset {
                if (RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.NORMAL) {
                    RestoreCoordinator.exclusive { }
                }
                if (RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.RESTORE_PREPARING) {
                    RestoreCoordinator.quiesceReactiveOperations()
                }
                RestoreCoordinator.awaitFiniteDrained()
            }
        } finally {
            SynthXDatabase.closeInstance()
            SongRepositoryImpl.resetForTest()
        }
    }

    // ------------------------------------------------------------------ 1. admission closes

    @Test
    fun closingAdmissionReplacesTheWholeNavGraphWithTheMaintenanceSurface() {
        startNormalShell()
        navigateTo(Screen.Library.route)
        assertTrue(
            "A DB-backed screen should be collecting before the restore starts",
            awaitReactiveOperations { it > 0 },
        )

        enterRestorePreparing()

        awaitTag(ShellTestTags.RestoreMaintenanceSurface, present = true)
        awaitTag(ShellTestTags.NavHost, present = false)

        // Already-admitted work drains through RestoreCoordinator's reactive-operation drain.
        runBlocking { RestoreCoordinator.quiesceReactiveOperations() }
        assertEquals(0, RestoreCoordinator.activeReactiveOperationCountForTest())
    }

    // ------------------------------------------------------- 2. Library is truly unreachable

    @Test
    fun libraryCannotBeReachedOrCollectWhileRestoreIsPreparing() {
        // Compose the normal shell first and visit Library, so the nav controller already holds
        // a graph containing the route. A later navigate() is therefore a genuine attempt to
        // reach it, not a no-op against a controller that was never set up.
        startNormalShell()
        navigateTo(Screen.Library.route)
        assertTrue(awaitReactiveOperations { it > 0 })

        enterRestorePreparing()
        runBlocking { RestoreCoordinator.quiesceReactiveOperations() }
        awaitTag(ShellTestTags.RestoreMaintenanceSurface, present = true)
        awaitTag(ShellTestTags.NavHost, present = false)
        assertEquals(0, RestoreCoordinator.activeReactiveOperationCountForTest())

        // The deliberate attempt the discovery step found was possible: back out, then navigate
        // to Library. Nothing may compose, so LibraryViewModel is never constructed and no
        // repository Flow is ever subscribed.
        attemptNavigateWhileTheGraphIsGone(Screen.Home.route)
        attemptNavigateWhileTheGraphIsGone(Screen.Library.route)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(ShellTestTags.NavHost).assertCountEquals(0)
        awaitTag(ShellTestTags.RestoreMaintenanceSurface, present = true)
        assertEquals(
            "No Room Flow may be admitted while admission is closed",
            0,
            RestoreCoordinator.activeReactiveOperationCountForTest(),
        )
    }

    // ------------------------------------------------------------------- 3. early abort

    @Test
    fun abortingBeforeRoomCloseResumesTheNormalShellWithoutARestart() {
        startNormalShell()

        enterRestorePreparing()
        awaitTag(ShellTestTags.RestoreMaintenanceSurface, present = true)
        awaitTag(ShellTestTags.NavHost, present = false)

        // The failure-before-close path in RestoreTransaction: admission reopens, and no
        // Terminal is ever published.
        RestoreCoordinator.markNormal()

        awaitTag(ShellTestTags.NavHost, present = true)
        awaitTag(ShellTestTags.RestoreMaintenanceSurface, present = false)
        assertEquals(RestoreCoordinator.Terminal.NORMAL, RestoreCoordinator.current())

        // And the shell is live again, not a husk: a DB-backed screen collects once more.
        navigateTo(Screen.Library.route)
        assertTrue(
            "The resumed shell must be able to admit Room Flows again",
            awaitReactiveOperations { it > 0 },
        )
    }

    // -------------------------------------------------------- 4. Terminal handover unchanged

    @Test
    fun markClosedStillHandsOverFromMaintenanceToTheTerminalSurface() {
        startNormalShell()
        enterRestorePreparing()
        runBlocking { RestoreCoordinator.quiesceReactiveOperations() }
        awaitTag(ShellTestTags.RestoreMaintenanceSurface, present = true)

        RestoreCoordinator.markClosed()

        // Terminal outranks the maintenance surface the moment it is published.
        awaitTag(ShellTestTags.RestoreMaintenanceSurface, present = false)
        awaitTag(ShellTestTags.NavHost, present = false)
        composeRule.onNodeWithText("Restart required").assertExists()
        assertEquals(RestoreCoordinator.Terminal.RESTART_REQUIRED, RestoreCoordinator.current())
    }

    // ------------------------------------------------------------------------ helpers

    private fun startNormalShell() {
        composeRule.setContent {
            SynthXTheme {
                navController = rememberNavController()
                MainScaffold(navController)
            }
        }
        awaitTag(ShellTestTags.NavHost, present = true)
    }

    /**
     * Enters the window exactly as RestoreTransaction does. [RestoreCoordinator.exclusive] does
     * not restore the previous mode on return, so the window stays open until something
     * explicitly closes it — which is the behaviour under test.
     */
    private fun enterRestorePreparing() {
        runBlocking { RestoreCoordinator.exclusive { } }
        assertEquals(
            RestoreCoordinator.Mode.RESTORE_PREPARING,
            RestoreCoordinator.currentMode(),
        )
    }

    /** Fails the test when the route is not registered or not reached (issue #215). */
    private fun navigateTo(route: String) {
        composeRule.runOnUiThread { navController.navigate(route) }
        composeRule.waitForIdle()
        assertEquals(
            "navigate($route) did not reach its destination",
            route,
            composeRule.runOnIdle {
                navController.currentDestination?.route
            },
        )
        if (route == Screen.Library.route) awaitTag(PerfTestTags.LibraryScreen, present = true)
    }

    /**
     * The one deliberate attempt that may fail: the nav graph is out of composition, so whether
     * the controller accepts the call does not matter. What matters is that nothing composes.
     */
    private fun attemptNavigateWhileTheGraphIsGone(route: String) {
        composeRule.runOnUiThread { runCatching { navController.navigate(route) } }
        composeRule.waitForIdle()
    }

    private fun awaitTag(tag: String, present: Boolean) {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == present
        }
    }

    private fun awaitReactiveOperations(predicate: (Int) -> Boolean): Boolean = runCatching {
        composeRule.waitUntil(TIMEOUT_MS) {
            predicate(RestoreCoordinator.activeReactiveOperationCountForTest())
        }
        true
    }.getOrDefault(false)

    @Test
    fun emergencyTerminalOffersDiagnosticsBackupRecoveryAndClose() {
        composeRule.setContent {
            SynthXTheme { RestoreTerminalContent(RestoreCoordinator.Terminal.EMERGENCY) }
        }
        composeRule.onNodeWithText("Export diagnostics").assertExists()
        composeRule.onNodeWithText("Choose backup").assertExists()
        composeRule.onNodeWithText("Close").assertExists()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
