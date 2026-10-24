package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.db.AlbumEntity
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.FakePlaybackEngine.Companion.song
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which bottom-bar tab is lit in the real shell: the tab whose root is the nearest one at or
 * below the visible screen on the back stack. Tests named `tap...` assert that tab taps behave as
 * they do for a screen with no highlight rule, and must not change with the highlight.
 */
@RunWith(AndroidJUnit4::class)
class TabHighlightInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val home = Screen.Home.route
    private val library = Screen.Library.route
    private val search = Screen.Search.route
    private val songs = Screen.Songs.route
    private val album = Screen.AlbumDetail.ROUTE
    private val current = song("tab-highlight-current", albumId = "tab-highlight-album")

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun text(id: Int) = shell.context.getString(id)
    private val homeTab get() = text(R.string.nav_home)
    private val libraryTab get() = text(R.string.nav_library)
    private val searchTab get() = text(R.string.nav_search)

    private val albumRoute = Screen.AlbumDetail.createRoute("tab-album", "Tab Album")
    private val artistRoute = Screen.ArtistDetail.createRoute("tab-artist", "Tab Artist")
    private val playlistRoute = Screen.PlaylistDetail.createRoute("tab-playlist", "Tab Playlist")

    private fun selectedTabs(): List<String> {
        listOf(homeTab, libraryTab, searchTab).forEach { tab -> shell.tab(tab).assertExists() }
        return listOf(homeTab, libraryTab, searchTab).filter { tab ->
            composeRule.onAllNodes(hasText(tab) and isSelectable() and isSelected())
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertLit(tab: String, where: String) =
        assertEquals("tabs lit on $where (${shell.route()})", listOf(tab), selectedTabs())

    @Test fun eachTabRootLightsItself() {
        shell.startShell()
        assertLit(homeTab, "Home root")
        shell.clickTab(libraryTab)
        assertLit(libraryTab, "Library root")
        shell.clickTab(searchTab)
        assertLit(searchTab, "Search root")
        shell.clickTab(homeTab)
        assertLit(homeTab, "Home root again")
    }

    @Test fun pagesOpenedFromSearchLightSearch() {
        shell.startShell()
        shell.clickTab(searchTab)
        listOf(albumRoute, artistRoute, playlistRoute).forEach { target ->
            shell.navigate(target)
            assertLit(searchTab, "page opened from Search")
            shell.pressBack()
            assertEquals(search, shell.route())
        }
    }

    @Test fun libraryListsAndTheirPagesLightLibrary() {
        shell.startShell()
        shell.clickTab(libraryTab)
        listOf(Screen.AlbumList.route, Screen.PlaylistList.route, Screen.ArtistList.route).forEach { list ->
            shell.navigate(list)
            assertLit(libraryTab, "Library list")
            shell.pressBack()
            assertEquals(library, shell.route())
        }
        shell.navigate(Screen.AlbumList.route)
        shell.navigate(albumRoute)
        assertLit(libraryTab, "album opened from the album list")
    }

    @Test fun everyScreenWithTheBarLightsExactlyOneTab() {
        val barScreens = listOf(
            Screen.Songs.route,
            Screen.Starred.route,
            Screen.ArtistList.route,
            Screen.AlbumList.route,
            Screen.PlaylistList.route,
            Screen.CreatePlaylist.route,
            artistRoute,
            albumRoute,
            Screen.GroupDetail.createRoute("tab-group", "Tab Group"),
            playlistRoute,
        )
        shell.startShell()
        barScreens.forEach { target ->
            shell.navigate(target)
            assertEquals("tabs lit on ${shell.route()} straight from Home", 1, selectedTabs().size)
            shell.pressBack()
        }
    }

    @Test fun backFromASubScreenKeepsTheParentTabLit() {
        shell.startShell()
        shell.clickTab(searchTab)
        shell.navigate(albumRoute)
        shell.pressBack()
        assertEquals(search, shell.route())
        assertLit(searchTab, "Search root after Back")
    }

    @Test fun albumOpenedFromTheExpandedPlayerOnHomeLightsHome() {
        withSeededSong {
            shell.startShell(FakePlaybackEngine(current))
            goToAlbumFromTheExpandedPlayer()
            assertLit(homeTab, "album opened from the player over Home")
        }
    }

    @Test fun albumOpenedFromTheExpandedPlayerOverSearchLightsSearch() {
        withSeededSong {
            shell.startShell(FakePlaybackEngine(current))
            shell.clickTab(searchTab)
            goToAlbumFromTheExpandedPlayer()
            assertLit(searchTab, "album opened from the player over Search")
        }
    }

    @Test fun albumOpenedFromTheExpandedPlayerOverLibraryLightsLibrary() {
        withSeededSong {
            shell.startShell(FakePlaybackEngine(current))
            shell.clickTab(libraryTab)
            shell.navigate(songs)
            goToAlbumFromTheExpandedPlayer()
            assertLit(libraryTab, "album opened from the player over Songs")
        }
    }

    @Test fun aRestoredTabPlaceLightsThatTab() {
        shell.startShell()
        shell.clickTab(libraryTab)
        shell.navigate(songs)
        shell.clickTab(searchTab)
        shell.navigate(albumRoute)
        assertLit(searchTab, "Search album")

        shell.clickTab(homeTab)
        assertLit(homeTab, "Home")

        shell.clickTab(searchTab)
        assertEquals("Search comes back on its album", album, shell.route())
        assertLit(searchTab, "restored Search album")

        shell.clickTab(homeTab)
        shell.clickTab(libraryTab)
        assertEquals("Library comes back on Songs", songs, shell.route())
        assertLit(libraryTab, "restored Songs")
    }

    @Test fun extraEntriesBelowFromAFirstRunSessionDoNotChangeTheLitTab() {
        shell.startShell(start = Screen.Onboarding.route)
        composeRule.runOnUiThread {
            shell.navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Onboarding.route) { inclusive = true }
            }
        }
        composeRule.waitForIdle()
        repeat(2) {
            shell.clickTab(libraryTab)
            shell.clickTab(homeTab)
        }
        assertLit(homeTab, "Home after the churn")

        shell.clickTab(searchTab)
        shell.navigate(albumRoute)
        assertLit(searchTab, "Search album over extra entries")

        shell.clickTab(libraryTab)
        shell.navigate(songs)
        assertLit(libraryTab, "Songs over extra entries")
    }

    @Test fun backFromAScreenWithoutTheBarKeepsTheParentTabLit() {
        shell.startShell()
        shell.clickTab(libraryTab)
        shell.navigate(songs)
        shell.navigate(Screen.Settings.route)
        assertEquals(Screen.Settings.route, shell.route())
        shell.pressBack()
        assertEquals(songs, shell.route())
        assertLit(libraryTab, "Songs after Back from Settings")
    }

    @Test fun tapLibraryOnASearchOpenedAlbumStillResetsLibrary() {
        shell.startShell()
        shell.clickTab(searchTab)
        shell.navigate(albumRoute)

        shell.clickTab(libraryTab)

        assertEquals(library, shell.route())
        assertEquals(home, shell.previousRoute())
        assertFalse("the album page is dropped", shell.onStack(album))
    }

    @Test fun tapSearchOnASearchOpenedAlbumStaysOnTheAlbum() {
        shell.startShell()
        shell.clickTab(searchTab)
        shell.navigate(albumRoute)

        shell.clickTab(searchTab)

        assertEquals(album, shell.route())
        assertEquals(search, shell.previousRoute())
        assertTrue(shell.onStack(search))
    }

    @Test fun tapHomeOnALibrarySubScreenGoesHomeAndKeepsLibrarysPlace() {
        shell.startShell()
        shell.clickTab(libraryTab)
        shell.navigate(songs)

        shell.clickTab(homeTab)

        assertEquals(home, shell.route())
        assertFalse(shell.onStack(songs))
    }

    private fun withSeededSong(block: () -> Unit) {
        val database = SynthXDatabase.getInstance(shell.context)
        runBlocking {
            database.albumDao().upsertAlbum(
                AlbumEntity("tab-highlight-album", "Tab Highlight Album", year = null, albumArtUri = null),
            )
            database.songDao().upsertSong(
                SongEntity(
                    id = current.id,
                    title = current.title,
                    albumId = "tab-highlight-album",
                    duration = 60_000L,
                    filePath = current.filePath,
                    albumArtUri = null,
                    year = null,
                    dateAdded = 1L,
                ),
            )
        }
        try {
            block()
        } finally {
            runBlocking {
                database.songDao().deleteSongById(current.id)
                database.albumDao().deleteOrphanAlbums()
            }
        }
    }

    private fun goToAlbumFromTheExpandedPlayer() {
        composeRule.onNodeWithTag(PerfTestTags.MiniPlayer).performClick()
        composeRule.waitForIdle()
        assertTrue("player did not expand", shell.tagDisplayed(PerfTestTags.QueueButton))

        // Semantics click: the expanded sheet's buttons sit in a gesture-driven layer.
        composeRule.onAllNodes(hasContentDescription(text(R.string.cd_more_options)) and isEnabled())
            .onFirst().performSemanticsAction(SemanticsActions.OnClick)
        shell.awaitText(text(R.string.actions_go_to_album))
        composeRule.onNode(hasText(text(R.string.actions_go_to_album))).performClick()
        composeRule.waitForIdle()

        assertEquals(album, shell.route())
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { !shell.tagDisplayed(PerfTestTags.QueueButton) }
    }
}
