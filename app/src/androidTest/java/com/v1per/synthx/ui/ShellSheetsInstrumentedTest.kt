package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two sheets the shell hosts for screens deep in the nav graph: the Songs sort sheet and the
 * playlist group picker. A pick closes the sheet and applies once; dismissing applies nothing.
 * Seeds a few uniquely named rows into the live debug database and removes them afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ShellSheetsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val database get() = SynthXDatabase.getInstance(shell.context)
    private val future = System.currentTimeMillis() + 1_000_000_000L
    private val olderSong = seedSong("sheets-older", "0000 Sheets A", future)
    private val newerSong = seedSong("sheets-newer", "0000 Sheets B", future + 1_000L)
    private val group = PlaylistGroupEntity("sheets-group", "Sheets Group", position = 0, createdAt = 1L)
    private val playlist = PlaylistEntity(
        id = "sheets-playlist",
        name = "0000 Sheets Playlist",
        description = null,
        groupId = null,
        position = 0,
        createdAt = 1L,
        updatedAt = 1L,
        coverArtUri = null,
    )

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() {
        try {
            runBlocking {
                database.songDao().deleteSongById(olderSong.id)
                database.songDao().deleteSongById(newerSong.id)
                database.playlistDao().deletePlaylist(playlist.id)
                database.playlistDao().deleteGroup(group.id)
            }
        } finally {
            shell.tearDown()
        }
    }

    private fun text(id: Int) = shell.context.getString(id)

    private fun seedSong(id: String, title: String, added: Long) = SongEntity(
        id = id,
        title = title,
        albumId = null,
        duration = 1,
        filePath = "file:///$id.mp3",
        albumArtUri = null,
        year = null,
        dateAdded = added,
    )

    private fun top(title: String): Float =
        composeRule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun openSongsWithTwoSeededSongs() {
        runBlocking {
            database.songDao().upsertSong(olderSong)
            database.songDao().upsertSong(newerSong)
        }
        shell.startShell()
        shell.navigate(Screen.Songs.route)
        shell.awaitText(newerSong.title)
        assertTrue("Recently Added lists the newer song first", top(newerSong.title) < top(olderSong.title))
        composeRule.onNode(hasContentDescription(text(R.string.cd_sort))).performClick()
        shell.awaitText(text(R.string.sort_by))
    }

    @Test fun sortSheetPickClosesTheSheetAndAppliesTheOrder() {
        openSongsWithTwoSeededSongs()
        composeRule.onNodeWithText(text(R.string.sort_title)).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(text(R.string.sort_by)) == 0 }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { top(olderSong.title) < top(newerSong.title) }
    }

    @Test fun sortSheetBackClosesTheSheetAndKeepsTheOrder() {
        openSongsWithTwoSeededSongs()
        shell.pressBackKey()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(text(R.string.sort_by)) == 0 }
        assertEquals(Screen.Songs.route, shell.route())
        assertTrue("dismissing must not re-sort", top(newerSong.title) < top(olderSong.title))
    }

    private fun openGroupPickerForTheSeededPlaylist() {
        runBlocking {
            database.playlistDao().insertGroup(group)
            database.playlistDao().insertPlaylist(playlist)
        }
        shell.startShell()
        shell.navigate(Screen.PlaylistList.route)
        shell.awaitText(playlist.name)
        composeRule.onNode(
            hasContentDescription(text(R.string.cd_more_options)) and hasAnyAncestor(hasText(playlist.name)),
        ).performClick()
        shell.awaitText(text(R.string.action_add_to_group))
        composeRule.onNodeWithText(text(R.string.action_add_to_group)).performClick()
        // The menu item is gone; the same words are now the sheet's title.
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(group.name) >= 1 }
        shell.awaitText(text(R.string.action_add_to_group))
    }

    private fun groupOfSeededPlaylist(): String? = runBlocking {
        database.playlistDao().getAllPlaylists().first().single { it.id == playlist.id }.groupId
    }

    @Test fun groupPickerPickClosesTheSheetAndMovesThePlaylist() {
        openGroupPickerForTheSeededPlaylist()
        // The sheet is composed last, so its row is the last node with the group's name.
        composeRule.onAllNodesWithText(group.name).onLast().performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { groupOfSeededPlaylist() == group.id }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(text(R.string.action_add_to_group)) == 0 }
    }

    @Test fun groupPickerBackClosesTheSheetAndMovesNothing() {
        openGroupPickerForTheSeededPlaylist()
        shell.pressBackKey()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(text(R.string.action_add_to_group)) == 0 }
        assertEquals(Screen.PlaylistList.route, shell.route())
        assertEquals("dismissing must not move the playlist", null, groupOfSeededPlaylist())
    }
}
