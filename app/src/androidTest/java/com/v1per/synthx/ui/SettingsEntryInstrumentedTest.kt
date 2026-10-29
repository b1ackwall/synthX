package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
 * Settings is reached from the Home settings button only: the button opens it, Back returns to Home, and the
 * Library and Search bars carry just the centred wordmark.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEntryInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)

    // Read first: initializing a destination object before Screen itself leaves it null in Screen's own init.
    private val home = Screen.Home.route

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun label(id: Int) = shell.context.getString(id)

    private fun settingsButtonCount() =
        composeRule.onAllNodesWithContentDescription(label(R.string.screen_settings)).fetchSemanticsNodes().size

    private fun assertWordmarkAtStart(screen: String) {
        val wordmark = composeRule.onAllNodesWithContentDescription(label(R.string.cd_wordmark)).onFirst()
        val bounds = wordmark.fetchSemanticsNode().boundsInRoot
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val density = composeRule.density.density
        assertTrue(
            "$screen wordmark centre ${bounds.center.x} is not in the left half of $rootWidth",
            bounds.center.x < rootWidth / 2f,
        )
        assertTrue(
            "$screen wordmark starts ${bounds.left} px in, past the start padding",
            bounds.left <= START_PADDING_LIMIT_DP * density,
        )
    }

    @Test fun homeSettingsButtonOpensSettings() {
        shell.startShell()
        composeRule.onNodeWithTag(HOME_SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.route() == Screen.Settings.route }
        assertEquals(Screen.Settings.route, shell.route())
    }

    @Test fun backFromSettingsOpenedFromHomeReturnsToHome() {
        shell.startShell()
        composeRule.onNodeWithTag(HOME_SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.route() == Screen.Settings.route }
        shell.pressBack()
        assertEquals(home, shell.route())
    }

    @Test fun systemBackKeyFromSettingsOpenedFromHomeReturnsToHome() {
        shell.startShell()
        composeRule.onNodeWithTag(HOME_SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.route() == Screen.Settings.route }
        shell.pressBackKey()
        assertEquals(home, shell.route())
    }

    @Test fun libraryBarHasNoSettingsButton() {
        shell.startShell()
        shell.clickTab(label(R.string.nav_library))
        assertEquals(Screen.Library.route, shell.route())
        assertEquals("Library settings buttons", 0, settingsButtonCount())
    }

    @Test fun searchBarHasNoSettingsButton() {
        shell.startShell()
        shell.clickTab(label(R.string.nav_search))
        assertEquals(Screen.Search.route, shell.route())
        assertEquals("Search settings buttons", 0, settingsButtonCount())
    }

    @Test fun libraryWordmarkIsStartAligned() {
        shell.startShell()
        shell.clickTab(label(R.string.nav_library))
        assertWordmarkAtStart("Library")
    }

    @Test fun searchWordmarkIsStartAligned() {
        shell.startShell()
        shell.clickTab(label(R.string.nav_search))
        assertWordmarkAtStart("Search")
    }

    @Test fun homeKeepsExactlyOneSettingsButton() {
        shell.startShell()
        assertEquals(1, settingsButtonCount())
        composeRule.onAllNodesWithContentDescription(label(R.string.cd_wordmark)).fetchSemanticsNodes().also {
            assertEquals("Home wordmarks", 1, it.size)
        }
        assertTrue(
            hasContentDescription(label(R.string.screen_settings)).matches(
                composeRule.onNodeWithTag(HOME_SETTINGS_BUTTON_TAG).fetchSemanticsNode(),
            ),
        )
    }

    private companion object {
        const val HOME_SETTINGS_BUTTON_TAG = "home_settings_button"
        const val START_PADDING_LIMIT_DP = 32f
    }
}
