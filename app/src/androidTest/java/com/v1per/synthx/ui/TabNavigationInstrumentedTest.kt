package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three bottom-nav tabs in the real shell, checked through the visible route, the previous
 * route, which routes are still on the back stack, and system Back. Tests named `pinned...`
 * assert today's behaviour on purpose; a deliberate behaviour change must update them.
 */
@RunWith(AndroidJUnit4::class)
class TabNavigationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val home = Screen.Home.route
    private val library = Screen.Library.route
    private val search = Screen.Search.route
    private val songs = Screen.Songs.route
    private val album = Screen.AlbumDetail.ROUTE

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun label(id: Int) = shell.context.getString(id)
    private val homeTab get() = label(com.v1per.synthx.R.string.nav_home)
    private val libraryTab get() = label(com.v1per.synthx.R.string.nav_library)
    private val searchTab get() = label(com.v1per.synthx.R.string.nav_search)

    @Test fun libraryTabFromDeepInsideLibraryResetsToItsRoot() {
        shell.startShell()
        shell.clickTab(libraryTab)
        shell.navigate(songs)

        shell.clickTab(libraryTab)

        assertEquals(library, shell.route())
        assertEquals(home, shell.previousRoute())
        assertFalse("Songs must not stay under Library's root", shell.onStack(songs))
        shell.assertBackWalk(listOf(library, home))
    }

    @Test fun switchingTabsKeepsEachTabsPlace() {
        shell.startShell()
        shell.clickTab(libraryTab)
        shell.navigate(songs)

        shell.clickTab(homeTab)
        assertEquals(home, shell.route())
        assertNull("Home is the only entry", shell.previousRoute())

        shell.clickTab(libraryTab)
        assertEquals("Library comes back where it was left", songs, shell.route())
        shell.assertBackWalk(listOf(songs, library, home))
    }

    @Test fun reselectingTheHomeOrLibraryRootChangesNothing() {
        shell.startShell()
        shell.clickTab(homeTab)
        assertEquals(home, shell.route())
        assertNull(shell.previousRoute())

        shell.clickTab(libraryTab)
        shell.clickTab(libraryTab)
        assertEquals(library, shell.route())
        shell.assertBackWalk(listOf(library, home))
    }

    @Test fun reselectingSearchFocusesItsField() {
        shell.startShell()
        shell.clickTab(searchTab)
        assertEquals(search, shell.route())
        val field = composeRule.onNode(hasSetTextAction())
        // Needs touch mode: out of touch mode the emulator focuses the field on its own.
        field.assertIsNotFocused()

        shell.clickTab(searchTab)

        assertEquals(search, shell.route())
        composeRule.waitUntil(ShellTestHost.WAIT_MS) {
            runCatching { field.assertIsFocused() }.isSuccess
        }
    }

    @Test fun backFromSongsGoesToLibraryThenHome() {
        shell.startShell()
        shell.clickTab(libraryTab)
        shell.navigate(songs)
        shell.assertBackWalk(listOf(songs, library, home))
    }

    /** PINNED current behaviour, issue #213 (question): no tab is lit on a sub-screen. */
    @Test fun pinnedNoTabIsHighlightedOnSubScreens() {
        shell.startShell()
        shell.clickTab(libraryTab)
        assertEquals("Library root lights Library", listOf(libraryTab), selectedTabs())
        listOf(
            songs,
            Screen.AlbumDetail.createRoute("tab-album", "Tab Album"),
            Screen.PlaylistDetail.createRoute("tab-playlist", "Tab Playlist"),
        ).forEach { target ->
            shell.navigate(target)
            assertEquals("tabs lit on ${shell.route()}", emptyList<String>(), selectedTabs())
        }
    }

    /**
     * PINNED current behaviour, issue #212: Library tapped on a page opened from Search resets
     * Library to its root and drops Search's page; Library's saved place returns one visit later.
     */
    @Test fun pinnedLibraryTabFromAnAlbumOpenedViaSearch() {
        shell.startShell()
        shell.clickTab(libraryTab)
        shell.navigate(songs)
        shell.clickTab(searchTab)
        assertEquals(search, shell.route())
        assertFalse("Songs is saved, not on the stack", shell.onStack(songs))

        shell.navigate(Screen.AlbumDetail.createRoute("search-album", "Search Album"))
        assertEquals(search, shell.previousRoute())

        shell.clickTab(libraryTab)
        assertEquals("Library opens at its root, not at Songs", library, shell.route())
        assertEquals(home, shell.previousRoute())
        assertFalse("the album page is dropped", shell.onStack(album))
        assertFalse(shell.onStack(songs))

        shell.clickTab(searchTab)
        assertEquals("Search comes back at its top", search, shell.route())
        assertFalse("the album page is gone for good", shell.onStack(album))

        shell.clickTab(libraryTab)
        assertEquals("Library's old place comes back", songs, shell.route())
        shell.assertBackWalk(listOf(songs, library, home))
    }

    /**
     * PINNED current behaviour, issue #211: after the wizard finishes in the same session, the
     * graph's start is still `onboarding`, so every tab switch adds to the back stack. Replays the
     * wizard's own navigate call from OnboardingRoutes.
     */
    @Test fun pinnedFirstRunSessionTabSwitchesGrowTheBackStack() {
        shell.startShell(start = Screen.Onboarding.route)
        composeRule.runOnUiThread {
            shell.navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Onboarding.route) { inclusive = true }
            }
        }
        composeRule.waitForIdle()
        assertEquals(home, shell.route())
        assertFalse(shell.onStack(Screen.Onboarding.route))

        repeat(3) {
            shell.clickTab(libraryTab)
            shell.clickTab(homeTab)
        }

        // A correct stack would be just [home]; today it is home, library x3 interleaved.
        shell.assertBackWalk(listOf(home, library, home, library, home, library, home))
        assertNull("the oldest entry is the first Home", shell.previousRoute())
    }

    private fun selectedTabs(): List<String> {
        listOf(homeTab, libraryTab, searchTab).forEach { tab -> shell.tab(tab).assertExists() }
        return listOf(homeTab, libraryTab, searchTab).filter { tab ->
            composeRule.onAllNodes(hasText(tab) and isSelectable() and isSelected())
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
