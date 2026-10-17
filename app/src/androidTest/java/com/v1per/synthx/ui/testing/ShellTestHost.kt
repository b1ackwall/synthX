package com.v1per.synthx.ui.testing

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestorePermitLeakCheck
import com.v1per.synthx.data.backup.RestoreStartup
import com.v1per.synthx.data.backup.TestWorkManager
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackController
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.ShellTestTags
import com.v1per.synthx.ui.screens.restore.RestoreStatusViewModel
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Hosts the real [MainScaffold] in a plain activity with a [FakePlaybackEngine] behind its
 * PlaybackViewModel. Drives it only through public behaviour: tab labels, test tags, routes,
 * and Back. Call [setUp] from `@Before` and [tearDown] from `@After`.
 */
internal class ShellTestHost(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
) {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    lateinit var navController: NavHostController
        private set

    fun setUp() {
        // WorkManager's manifest initializer is removed for androidTest; the shell reaches it.
        RestoreStartup.await()
        TestWorkManager.ensureInitialized(context)
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        RestoreStartup.resetForTest()
        DatabaseStartupGate.markReadyForTest()
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    fun tearDown() {
        try {
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

    /** Puts a PlaybackViewModel on [engine] into the activity's store; MainScaffold then gets it. */
    fun installPlayback(engine: FakePlaybackEngine): PlaybackViewModel {
        lateinit var viewModel: PlaybackViewModel
        rule.runOnUiThread {
            viewModel = ViewModelProvider(
                rule.activity,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        PlaybackViewModel(PlaybackController(engine)) as T
                },
            )[PlaybackViewModel::class.java]
        }
        return viewModel
    }

    fun startShell(
        engine: FakePlaybackEngine = FakePlaybackEngine(),
        start: String = Screen.Home.route,
        restoreStatusViewModel: RestoreStatusViewModel? = null,
        wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ): PlaybackViewModel {
        val viewModel = installPlayback(engine)
        rule.setContent {
            wrap {
                SynthXTheme {
                    navController = rememberNavController()
                    MainScaffold(navController, startDestination = start, restoreStatusViewModel = restoreStatusViewModel)
                }
            }
        }
        rule.waitUntil(WAIT_MS) { tagCount(ShellTestTags.NavHost) > 0 }
        rule.waitForIdle()
        return viewModel
    }

    /** Navigates and fails the test when the route is not registered or not reached. */
    fun navigate(route: String) {
        rule.runOnUiThread { navController.navigate(route) }
        rule.waitForIdle()
        val reached = route()
        val matches = reached == route ||
            (reached != null && '{' in reached && reached.substringBefore('/') == route.substringBefore('/'))
        assertTrue("navigate($route) did not reach its destination; current route is $reached", matches)
    }

    fun route(): String? = rule.runOnIdle { navController.currentDestination?.route }

    fun previousRoute(): String? = rule.runOnIdle { navController.previousBackStackEntry?.destination?.route }

    /** True when a destination with this route (or route pattern) is on the back stack. */
    fun onStack(route: String): Boolean = rule.runOnIdle {
        runCatching { navController.getBackStackEntry(route) }.isSuccess
    }

    fun tab(label: String): SemanticsNodeInteraction =
        rule.onNode(hasText(label) and isSelectable() and hasClickAction())

    fun clickTab(label: String) {
        tab(label).performClick()
        rule.waitForIdle()
    }

    fun pressBack() {
        Espresso.pressBack()
        rule.waitForIdle()
    }

    /** Back through the instrumentation; reaches dialog windows where Espresso.pressBack can fail. */
    fun pressBackKey() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    /** Presses Back once per entry and checks each route, newest first. Leaves the oldest entry. */
    fun assertBackWalk(newestToOldest: List<String>) {
        assertEquals("starting route", newestToOldest.first(), route())
        newestToOldest.drop(1).forEachIndexed { index, expected ->
            pressBack()
            assertEquals("route after Back #${index + 1}", expected, route())
        }
    }

    fun tagCount(tag: String): Int = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    fun textCount(text: String): Int = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    fun tagDisplayed(tag: String): Boolean =
        tagCount(tag) > 0 && rule.onNodeWithTag(tag).isDisplayed()

    fun awaitText(text: String) = rule.waitUntil(WAIT_MS) { textCount(text) > 0 }

    companion object {
        const val WAIT_MS = 10_000L
    }
}
