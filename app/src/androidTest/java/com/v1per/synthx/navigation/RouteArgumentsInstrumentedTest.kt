package com.v1per.synthx.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Names travel inside detail routes. Each `createRoute` must encode the name so that a name with
 * route syntax in it (`/`, `%`, `?`, `#`) still opens the right screen and shows the name exactly.
 * Uri.encode returns null on the JVM test setup, so this runs on a device.
 */
@RunWith(AndroidJUnit4::class)
class RouteArgumentsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)

    private val awkwardNames = listOf(
        "AC/DC",
        "100% Pure",
        "What? Why!",
        "#1 Hits & More",
        "  spaced  out  ",
        "Café 🎵 Mix",
        "a+b=c",
    )

    private val seededPlaylistIds = awkwardNames.indices.map { "route-playlist-$it" }

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() {
        try {
            runBlocking {
                seededPlaylistIds.forEach { SynthXDatabase.getInstance(shell.context).playlistDao().deletePlaylist(it) }
            }
        } finally {
            shell.tearDown()
        }
    }

    private fun assertEachNameOpensAndShows(pattern: String, route: (index: Int, name: String) -> String) {
        shell.startShell()
        val wrong = awkwardNames.mapIndexedNotNull { index, name ->
            shell.navigate(route(index, name))
            composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(name) > 0 }
            val reached = shell.route()
            shell.pressBack()
            if (reached == pattern) null else "$name reached $reached"
        }
        assertEquals("names that did not open their screen", emptyList<String>(), wrong)
    }

    @Test fun albumNamesSurviveTheRoute() = assertEachNameOpensAndShows(Screen.AlbumDetail.ROUTE) { _, name ->
        Screen.AlbumDetail.createRoute("route-album", name)
    }

    @Test fun artistNamesSurviveTheRoute() = assertEachNameOpensAndShows(Screen.ArtistDetail.ROUTE) { _, name ->
        Screen.ArtistDetail.createRoute("route-artist", name)
    }

    /** A playlist page stays on its loading cover until the row exists, so real rows are seeded. */
    @Test fun playlistNamesSurviveTheRoute() {
        runBlocking {
            awkwardNames.forEachIndexed { index, name ->
                SynthXDatabase.getInstance(shell.context).playlistDao().insertPlaylist(
                    PlaylistEntity(
                        id = seededPlaylistIds[index],
                        name = name,
                        description = null,
                        groupId = null,
                        position = index,
                        createdAt = 1L,
                        updatedAt = 1L,
                        coverArtUri = null,
                    ),
                )
            }
        }
        assertEachNameOpensAndShows(Screen.PlaylistDetail.ROUTE) { index, name ->
            Screen.PlaylistDetail.createRoute(seededPlaylistIds[index], name)
        }
    }

    @Test fun groupNamesSurviveTheRoute() = assertEachNameOpensAndShows(Screen.GroupDetail.ROUTE) { _, name ->
        Screen.GroupDetail.createRoute("route-group", name)
    }
}
