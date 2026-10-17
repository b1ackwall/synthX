package com.v1per.synthx.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the dialog with the same show/hide/confirm wiring SongActionsHost uses (target state
 * set, dialog rendered while non-null, removal only from onConfirm). The full host needs the
 * whole MainScaffold, so the removal call is a counter standing in for
 * SongActionsViewModel.removeFromPlaylist.
 */
@RunWith(AndroidJUnit4::class)
class RemoveFromPlaylistDialogInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var removals = 0
    private var open by mutableStateOf(true)

    private fun show() {
        composeRule.setContent {
            if (open) {
                RemoveFromPlaylistDialog(
                    songTitle = "Night Drive",
                    playlistName = "Road Trip",
                    onConfirm = { removals++; open = false },
                    onDismiss = { open = false },
                )
            }
        }
    }

    @Test
    fun messageNamesSongAndPlaylist() {
        show()
        composeRule.onNodeWithText(
            context.getString(R.string.remove_from_playlist_dialog_message, "Night Drive", "Road Trip")
        ).assertIsDisplayed()
    }

    @Test
    fun cancelDoesNotRemoveAndClosesDialog() {
        show()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeRule.waitForIdle()

        assertEquals(0, removals)
        composeRule.onNodeWithText(
            context.getString(R.string.remove_from_playlist_dialog_title)
        ).assertDoesNotExist()
    }

    @Test
    fun confirmRemovesExactlyOnceAndClosesDialog() {
        show()
        composeRule.onNodeWithText(
            context.getString(R.string.remove_from_playlist_dialog_confirm)
        ).performClick()
        composeRule.waitForIdle()

        assertEquals(1, removals)
        composeRule.onNodeWithText(
            context.getString(R.string.remove_from_playlist_dialog_title)
        ).assertDoesNotExist()
    }
}
