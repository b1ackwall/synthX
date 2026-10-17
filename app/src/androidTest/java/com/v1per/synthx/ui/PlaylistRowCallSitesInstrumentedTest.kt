package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-screen companion of PlaylistRowMenuInstrumentedTest: the playlist row's menu at its three
 * real call sites (Search, Group detail, the Playlists list) inside the real shell, over uniquely
 * named rows seeded into the live debug database and removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistRowCallSitesInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val database get() = SynthXDatabase.getInstance(shell.context)
    private val group = PlaylistGroupEntity("rowsites-group", "0000 RowSites Group", position = 0, createdAt = 1L)
    private val otherGroup = PlaylistGroupEntity("rowsites-other", "0000 RowSites Other", position = 1, createdAt = 1L)
    private val grouped = playlist("rowsites-grouped", "0000 RowSites Grouped", group.id)
    private val ungrouped = playlist("rowsites-ungrouped", "0000 RowSites Loose", null)

    @Before fun setUp() {
        shell.setUp()
        runBlocking {
            database.playlistDao().insertGroup(group)
            database.playlistDao().insertGroup(otherGroup)
            database.playlistDao().insertPlaylist(grouped)
            database.playlistDao().insertPlaylist(ungrouped)
        }
    }

    @After fun tearDown() {
        try {
            runBlocking {
                database.playlistDao().deletePlaylist(grouped.id)
                database.playlistDao().deletePlaylist(ungrouped.id)
                database.playlistDao().deleteGroup(group.id)
                database.playlistDao().deleteGroup(otherGroup.id)
            }
        } finally {
            shell.tearDown()
        }
    }

    private fun playlist(id: String, name: String, groupId: String?) = PlaylistEntity(
        id = id, name = name, description = null, groupId = groupId,
        position = 0, createdAt = 1L, updatedAt = 1L, coverArtUri = null,
    )

    private fun text(id: Int) = shell.context.getString(id)

    private fun openMenuOf(name: String) {
        shell.awaitText(name)
        composeRule.onNode(
            hasContentDescription(text(R.string.cd_more_options)) and hasAnyAncestor(hasText(name) and !hasSetTextAction()),
        ).performClick()
        shell.awaitText(text(R.string.action_rename))
    }

    private fun groupOf(id: String): String? = runBlocking {
        database.playlistDao().getAllPlaylists().first().single { it.id == id }.groupId
    }

    @Test fun searchPlaylistRowOffersRenameAndDeleteButNoGroupingEntries() {
        shell.startShell()
        shell.navigate(Screen.Search.route)
        composeRule.onNode(hasSetTextAction()).performTextInput(grouped.name)
        // The typed query is one match; the result row is the second.
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(grouped.name) >= 2 }
        openMenuOf(grouped.name)

        assertEquals(1, shell.textCount(text(R.string.action_rename)))
        assertEquals(1, shell.textCount(text(R.string.action_delete)))
        assertEquals("Search passes no grouping callbacks", 0, shell.textCount(text(R.string.action_remove_from_group)))
        assertEquals(0, shell.textCount(text(R.string.action_move_to_group)))
        assertEquals(0, shell.textCount(text(R.string.action_add_to_group)))
    }

    @Test fun groupDetailPlaylistRowOffersMoveAndRemoveAndRemoveReallyUngroups() {
        shell.startShell()
        shell.navigate(Screen.GroupDetail.createRoute(group.id, group.name))
        openMenuOf(grouped.name)

        assertEquals(1, shell.textCount(text(R.string.action_move_to_group)))
        assertEquals(1, shell.textCount(text(R.string.action_remove_from_group)))
        assertEquals(0, shell.textCount(text(R.string.action_add_to_group)))

        composeRule.onNodeWithText(text(R.string.action_remove_from_group)).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { groupOf(grouped.id) == null }
    }

    @Test fun playlistListUngroupedRowOffersAddToGroupButNotRemove() {
        shell.startShell()
        shell.navigate(Screen.PlaylistList.route)
        openMenuOf(ungrouped.name)

        assertEquals(1, shell.textCount(text(R.string.action_add_to_group)))
        assertEquals(0, shell.textCount(text(R.string.action_remove_from_group)))
        assertEquals(0, shell.textCount(text(R.string.action_move_to_group)))
    }
}
