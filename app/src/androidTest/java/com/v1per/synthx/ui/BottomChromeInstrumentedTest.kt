package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.FakePlaybackEngine.Companion.song
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom chrome (tab bar plus mini player) in the real shell: which routes show it, and that
 * hiding it keeps the player's state alive rather than removing it from composition.
 */
@RunWith(AndroidJUnit4::class)
class BottomChromeInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val current = song("current")

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    /** Written by hand on purpose: a new route must make someone decide bar or no bar. */
    private val barShownByRoute = linkedMapOf(
        Screen.Home.route to true,
        Screen.Library.route to true,
        Screen.Search.route to true,
        Screen.Songs.route to true,
        Screen.Starred.route to true,
        Screen.ArtistList.route to true,
        Screen.ArtistDetail.createRoute("chrome-artist", "Chrome Artist") to true,
        Screen.AlbumList.route to true,
        Screen.AlbumDetail.createRoute("chrome-album", "Chrome Album") to true,
        Screen.PlaylistList.route to true,
        Screen.CreatePlaylist.route to true,
        Screen.GroupDetail.createRoute("chrome-group", "Chrome Group") to true,
        Screen.PlaylistDetail.createRoute("chrome-playlist", "Chrome Playlist") to true,
        Screen.Settings.route to false,
        Screen.DeveloperSettings.route to false,
        Screen.LibraryManagement.route to false,
        Screen.Issues.route to false,
        Screen.ReplaceSong.route to false,
        Screen.BackupsAndRestore.route to false,
        Screen.Restore.route to false,
        Screen.Onboarding.route to false,
    )

    @Test fun bottomBarAndMiniPlayerShowExactlyOnTheTabAndLibraryRoutes() {
        shell.startShell(FakePlaybackEngine(current))
        val wrong = mutableListOf<String>()
        barShownByRoute.forEach { (target, expected) ->
            shell.navigate(target)
            val bar = shell.tagDisplayed(PerfTestTags.NavHome)
            val mini = shell.tagDisplayed(PerfTestTags.MiniPlayer)
            if (bar != expected || mini != expected) {
                wrong += "${shell.route()}: bar=$bar mini=$mini, expected $expected"
            }
        }
        assertEquals("routes with the wrong bottom chrome", emptyList<String>(), wrong)
    }

    @Test fun hiddenChromeStaysComposedSoThePlayerKeepsItsState() {
        shell.startShell(FakePlaybackEngine(current))
        val lost = barShownByRoute.keys.mapNotNull { target ->
            shell.navigate(target)
            val composed = shell.tagCount(PerfTestTags.MiniPlayer) > 0 && shell.tagCount(PerfTestTags.NavHome) > 0
            if (composed) null else shell.route()
        }
        assertEquals("routes where the bar or mini player left composition", emptyList<String?>(), lost)
    }

    @Test fun noSongMeansNoMiniPlayerButTheBarStillShows() {
        shell.startShell(FakePlaybackEngine(current = null))
        assertEquals(true, shell.tagDisplayed(PerfTestTags.NavHome))
        assertEquals(0, shell.tagCount(PerfTestTags.MiniPlayer))
    }

    @Test fun openQueueSurvivesAVisitToAScreenWithoutTheBar() {
        // The queue list only composes with at least one upcoming song.
        shell.startShell(FakePlaybackEngine(current, upcoming = listOf(song("next"))))
        composeRule.onNodeWithTag(PerfTestTags.MiniPlayer).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PerfTestTags.QueueButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PerfTestTags.QueueList).assertExists()

        shell.navigate(Screen.Settings.route)
        shell.pressBack()

        assertEquals(Screen.Home.route, shell.route())
        composeRule.onNodeWithTag(PerfTestTags.QueueList).assertExists()
    }

    private fun bottom(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom

    private fun top(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private val onePixelSlack = 1.5f

    @Test fun screensEndJustAboveTheMiniPlayerWhileASongIsLoaded() {
        shell.startShell(FakePlaybackEngine(current))
        shell.navigate(Screen.Library.route)
        val gap = top(PerfTestTags.MiniPlayer) - bottom(PerfTestTags.LibraryScreen)
        assertTrue("Library ends ${-gap}px under the mini player", gap >= -onePixelSlack)
        assertTrue("Library leaves a ${gap}px strip above the mini player", gap <= onePixelSlack)
    }

    @Test fun screensEndJustAboveTheBarWithNoSong() {
        shell.startShell(FakePlaybackEngine(current = null))
        shell.navigate(Screen.Library.route)
        // The bar is a 1 dp divider above the tab row, so its top sits one divider above the tab.
        val divider = composeRule.density.run { 1.dp.toPx() }
        val gap = top(PerfTestTags.NavHome) - divider - bottom(PerfTestTags.LibraryScreen)
        assertTrue("Library ends ${-gap}px under the bar", gap >= -onePixelSlack)
        assertTrue("Library leaves a ${gap}px strip above the bar", gap <= onePixelSlack)
    }

    @Test fun tappingTheMiniPlayerExpandsAndBackCollapses() {
        shell.startShell(FakePlaybackEngine(current))
        assertEquals(false, shell.tagDisplayed(PerfTestTags.QueueButton))
        composeRule.onNodeWithTag(PerfTestTags.MiniPlayer).performClick()
        composeRule.waitForIdle()
        assertEquals("expanded player shows its queue button", true, shell.tagDisplayed(PerfTestTags.QueueButton))

        shell.pressBack()

        assertEquals("Back collapses the player first", Screen.Home.route, shell.route())
        assertEquals(false, shell.tagDisplayed(PerfTestTags.QueueButton))
        assertEquals(true, shell.tagDisplayed(PerfTestTags.NavHome))
    }
}
