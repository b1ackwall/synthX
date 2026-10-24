package com.v1per.synthx.ui.screens.playlistdetail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The playlist page in the real shell for ids that the playlist list never produces: the cover
 * lifts, the top bar and its back arrow stay usable, and a playlist that does exist never shows the
 * not-found text. A library with no playlists is made by setting the live debug database's
 * playlists aside and putting them back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistNotFoundInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val database get() = SynthXDatabase.getInstance(shell.context)

    private var asidePlaylists: List<PlaylistEntity> = emptyList()
    private var asideMemberships: List<PlaylistSongEntity> = emptyList()
    private val seededIds = mutableListOf<String>()

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() {
        try {
            runBlocking {
                seededIds.forEach { database.playlistDao().deletePlaylist(it) }
                asidePlaylists.forEach { database.playlistDao().insertPlaylist(it) }
                asideMemberships.forEach { database.playlistDao().insertPlaylistSong(it) }
            }
        } finally {
            shell.tearDown()
        }
    }

    private fun setAllPlaylistsAside() = runBlocking {
        val dao = database.playlistDao()
        asidePlaylists = dao.getAllPlaylists().first()
        database.openHelper.readableDatabase
            .query("SELECT playlist_id, song_id, position, added_at FROM playlist_songs").use { cursor ->
                val rows = mutableListOf<PlaylistSongEntity>()
                while (cursor.moveToNext()) {
                    rows +=
                        PlaylistSongEntity(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getInt(2),
                            cursor.getLong(3),
                        )
                }
                asideMemberships = rows
            }
        asidePlaylists.forEach { dao.deletePlaylist(it.id) }
    }

    private fun seedPlaylist(id: String) = runBlocking {
        seededIds += id
        database.playlistDao().insertPlaylist(
            PlaylistEntity(
                id = id,
                name = "Seeded $id",
                description = null,
                groupId = null,
                position = 0,
                createdAt = 1L,
                updatedAt = 1L,
                coverArtUri = null,
            ),
        )
    }

    private fun openGhost(id: String = GHOST_ID) {
        shell.startShell()
        shell.navigate(Screen.PlaylistDetail.createRoute(id, GHOST_NAME))
    }

    @Test fun anUnknownIdInALibraryWithNoPlaylistsShowsNotFoundAndTheTopBar() {
        setAllPlaylistsAside()
        openGhost()

        shell.awaitText(NOT_FOUND)
        assertEquals("the top bar title is shown", 1, shell.textCount(GHOST_NAME))
        composeRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test fun theBackArrowLeavesTheNotFoundPage() {
        setAllPlaylistsAside()
        openGhost()
        shell.awaitText(NOT_FOUND)

        composeRule.onNode(hasContentDescription("Back")).performClick()
        composeRule.waitForIdle()

        assertNotEquals(Screen.PlaylistDetail.ROUTE, shell.route())
    }

    @Test fun aRealPlaylistWithNoSongsShowsItsEmptyTextNotNotFound() {
        seedPlaylist(SEEDED_ID)
        shell.startShell()
        shell.navigate(Screen.PlaylistDetail.createRoute(SEEDED_ID, GHOST_NAME))

        shell.awaitText(NO_SONGS)
        assertEquals(0, shell.textCount(NOT_FOUND))
    }

    @Test fun anUnknownIdBesideAnotherPlaylistShowsThePlaceholderCoverNotNotFound() {
        seedPlaylist(SEEDED_ID)
        openGhost()

        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagCount(PLAYLIST_DETAIL_COVER_TAG) > 0 }
        composeRule.waitForIdle()
        assertEquals(0, shell.textCount(NOT_FOUND))
    }

    @Test fun aPlaylistCreatedForTheOpenIdReplacesTheNotFoundPage() {
        setAllPlaylistsAside()
        openGhost(LATE_ID)
        shell.awaitText(NOT_FOUND)

        seedPlaylist(LATE_ID)

        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagCount(PLAYLIST_DETAIL_COVER_TAG) > 0 }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(NOT_FOUND) == 0 }
    }

    private companion object {
        const val GHOST_ID = "not-found-ghost"
        const val GHOST_NAME = "Ghost List"
        const val SEEDED_ID = "not-found-seeded"
        const val LATE_ID = "not-found-late"
        const val NOT_FOUND = "Playlist not found"
        const val NO_SONGS = "No songs in this playlist."
    }
}
