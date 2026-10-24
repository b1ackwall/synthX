package com.v1per.synthx.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuotedNameDialogsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun removeFromPlaylistQuotesTheSongAndThePlaylistWithTypographicQuotes() {
        composeRule.setContent {
            RemoveFromPlaylistDialog(songTitle = "Night Drive", playlistName = "Road Trip", onConfirm = {
            }, onDismiss = {})
        }

        composeRule.onNodeWithText(
            "Remove “Night Drive” from “Road Trip”? The song stays in your library.",
        ).assertIsDisplayed()
    }

    @Test
    fun deletePlaylistQuotesThePlaylistNameWithTypographicQuotes() {
        composeRule.setContent {
            DeletePlaylistDialog(playlistName = "Road Trip", onConfirm = {}, onDismiss = {})
        }

        composeRule.onNodeWithText(
            "This permanently removes “Road Trip”. The songs in it are not deleted. " +
                "This cannot be undone.",
        ).assertIsDisplayed()
    }

    @Test
    fun deleteGroupQuotesTheGroupNameWithTypographicQuotes() {
        composeRule.setContent {
            DeleteGroupDialog(groupName = "Weekend", onConfirm = {}, onDismiss = {})
        }

        composeRule.onNodeWithText(
            "This permanently removes “Weekend”. The playlists in it are not deleted — " +
                "they move back to your ungrouped playlists. This cannot be undone.",
        ).assertIsDisplayed()
    }
}
