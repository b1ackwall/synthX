package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five Library category rows in the real shell: a tap reaches the row's destination through
 * the ViewModel event, the screen's collector and the nav controller. One test per row.
 */
@RunWith(AndroidJUnit4::class)
class LibraryCategoryNavigationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)

    // Read first: initializing a destination object before Screen itself leaves it null in Screen's own init.
    private val library = Screen.Library.route

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun label(id: Int) = shell.context.getString(id)

    /** Taps the row inside the Library screen only, so a same-named bottom-bar tab is never hit. */
    private fun tapRowAndAssertRoute(rowLabel: String, expectedRoute: String) {
        shell.startShell()
        shell.clickTab(label(R.string.nav_library))
        assertEquals(library, shell.route())

        composeRule.onNode(hasText(rowLabel) and hasAnyAncestor(hasTestTag(PerfTestTags.LibraryScreen)))
            .performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.route() == expectedRoute }
        assertEquals(expectedRoute, shell.route())

        shell.pressBack()
        assertEquals("Back returns to Library", library, shell.route())
    }

    @Test fun songsRowOpensSongs() = tapRowAndAssertRoute(label(R.string.screen_songs), Screen.Songs.route)

    @Test fun starredRowOpensStarred() = tapRowAndAssertRoute(label(R.string.screen_starred), Screen.Starred.route)

    @Test fun artistsRowOpensArtistList() = tapRowAndAssertRoute(
        label(R.string.screen_artists),
        Screen.ArtistList.route,
    )

    @Test fun albumsRowOpensAlbumList() = tapRowAndAssertRoute(label(R.string.screen_albums), Screen.AlbumList.route)

    @Test fun playlistsRowOpensPlaylistList() =
        tapRowAndAssertRoute(label(R.string.screen_playlists), Screen.PlaylistList.route)
}
