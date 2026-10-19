package com.v1per.synthx.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PlaylistRow]'s grouping menu entries must be gated on whether the caller actually supplied a
 * real grouping callback, not merely on the playlist's own `groupId`/the caller's
 * `availableGroups`. Gated on `playlist.groupId != null` alone, [SearchScreen][
 * com.v1per.synthx.ui.screens.search.SearchScreen]'s omitted-callback call site would render
 * "Remove from group" for any grouped playlist, and invoking it would silently no-op against the
 * default `{}` lambda.
 *
 * Three scenarios below mirror the app's three actual call sites (Search, GroupDetail,
 * PlaylistList) rather than synthetic combinations, so a regression at any real site is caught
 * directly.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistRowMenuInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val groupedPlaylist = Playlist(
        id = "p1",
        name = "Grouped Playlist",
        groupId = "g1",
        albumArtUri = null,
    )

    private val ungroupedPlaylist = Playlist(
        id = "p2",
        name = "Ungrouped Playlist",
        groupId = null,
        albumArtUri = null,
    )

    private val groups =
        listOf(PlaylistGroup(id = "g1", name = "Group One"), PlaylistGroup(id = "g2", name = "Group Two"))

    @Test
    fun searchStyleRowWithNoGroupingCallbacksHidesBothGroupingEntries() {
        // Mirrors SearchScreen.playlistItems: no onShowGroupPicker/onRemoveFromGroup/availableGroups.
        composeRule.setContent {
            PlaylistRow(
                playlist = groupedPlaylist,
                onClick = {},
                onRename = {},
                onDelete = {},
            )
        }
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.cd_more_options),
        ).performClick()

        composeRule.onNodeWithText(context.getString(R.string.action_rename)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_delete)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.action_remove_from_group)).assertCountEquals(0)
        composeRule.onAllNodesWithText(context.getString(R.string.action_move_to_group)).assertCountEquals(0)
        composeRule.onAllNodesWithText(context.getString(R.string.action_add_to_group)).assertCountEquals(0)
    }

    @Test
    fun groupDetailStyleRowWithRealCallbacksShowsRemoveFromGroup() {
        // Mirrors GroupDetailScreen: real onShowGroupPicker + onRemoveFromGroup + availableGroups.
        composeRule.setContent {
            PlaylistRow(
                playlist = groupedPlaylist,
                onClick = {},
                onRename = {},
                onDelete = {},
                onShowGroupPicker = {},
                onRemoveFromGroup = {},
                availableGroups = groups,
            )
        }
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.cd_more_options),
        ).performClick()

        composeRule.onNodeWithText(context.getString(R.string.action_remove_from_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_move_to_group)).assertIsDisplayed()
    }

    @Test
    fun playlistListStyleRowWithNullRemoveCallbackShowsAddButNotRemove() {
        // Mirrors PlaylistListScreen's ungrouped-section call: real onShowGroupPicker, no
        // onRemoveFromGroup, ungrouped playlist. Proves the gating is explicitly safe here, not
        // just safe through the "always ungrouped" invariant.
        composeRule.setContent {
            PlaylistRow(
                playlist = ungroupedPlaylist,
                onClick = {},
                onRename = {},
                onDelete = {},
                onShowGroupPicker = {},
                availableGroups = groups,
            )
        }
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.cd_more_options),
        ).performClick()

        composeRule.onNodeWithText(context.getString(R.string.action_add_to_group)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.action_remove_from_group)).assertCountEquals(0)
    }
}
