package com.v1per.synthx.ui.screens.playlistlist

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.ui.components.PlaylistRow
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P8-T7 / B05: a half-typed playlist rename survives activity recreation. See
 * CreatePlaylistRecreationInstrumentedTest for why both installs go through [install].
 */
@RunWith(AndroidJUnit4::class)
class PlaylistRowRenameRecreationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val playlist = Playlist(id = "p1", name = "Old name", groupId = null, albumArtUri = null)

    @Composable
    private fun Row() = PlaylistRow(playlist = playlist, onClick = {}, onRename = {}, onDelete = {})

    private fun install() {
        composeRule.runOnUiThread { composeRule.activity.setContent { Row() } }
    }

    @Test
    fun renameDraftSurvivesActivityRecreation() {
        install()
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_more_options)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_rename)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("New nam")

        composeRule.activityRule.scenario.recreate()
        install()
        composeRule.waitForIdle()

        // Still in rename mode, with the draft rather than the stored name.
        composeRule.onNodeWithText("New nam").assertIsDisplayed()
    }
}
