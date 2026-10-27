package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Songs, Starred, Artists and Albums routes in the real shell. A fresh navigation steps the
 * paused clock one frame at a time: no frame may show the loading spinner, and the screen (found
 * by its top bar title) must appear once the data is ready.
 */
@RunWith(AndroidJUnit4::class)
class ListRoutesReadinessCoverInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)

    // Read first: initializing a destination object before Screen itself leaves it null in Screen's own init.
    private val library = Screen.Library.route
    private val spinner = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun spinnerCount() = composeRule.onAllNodes(spinner).fetchSemanticsNodes().size

    private fun assertCutsStraightToTheScreen(route: String, titleRes: Int) {
        check(library.isNotEmpty())
        val title = shell.context.getString(titleRes)
        shell.startShell()
        val baseline = spinnerCount()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { shell.navController.navigate(route) }
            var frames = 0
            do {
                composeRule.mainClock.advanceTimeByFrame()
                assertEquals("loading spinner shown on frame $frames of $route", baseline, spinnerCount())
                Thread.sleep(FRAME_PAUSE_MS)
                frames++
            } while (shell.textCount(title) == 0 && frames < MAX_FRAMES)
            assertTrue("$route never showed its screen within $MAX_FRAMES frames", shell.textCount(title) > 0)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertEquals(route, shell.route())
        assertEquals("spinner after ready on $route", baseline, spinnerCount())
    }

    @Test fun songsCutsStraightToTheScreen() = assertCutsStraightToTheScreen(Screen.Songs.route, R.string.screen_songs)

    @Test fun starredCutsStraightToTheScreen() = assertCutsStraightToTheScreen(
        Screen.Starred.route,
        R.string.screen_starred,
    )

    @Test fun artistListCutsStraightToTheScreen() =
        assertCutsStraightToTheScreen(Screen.ArtistList.route, R.string.screen_artists)

    @Test fun albumListCutsStraightToTheScreen() =
        assertCutsStraightToTheScreen(Screen.AlbumList.route, R.string.screen_albums)

    private companion object {
        const val FRAME_PAUSE_MS = 10L
        const val MAX_FRAMES = 400
    }
}
