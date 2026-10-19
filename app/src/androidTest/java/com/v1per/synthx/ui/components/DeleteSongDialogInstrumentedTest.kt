package com.v1per.synthx.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.DeleteSongPlaylistImpact
import com.v1per.synthx.data.repository.DeleteSongPreview
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteSongDialogInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun namesAffectedPlaylistsAndFlagsTheOneThatWillBecomeEmpty() {
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                DeleteSongDisclosure(
                    previewState = DeleteSongPreviewState.Ready(
                        DeleteSongPreview(
                            listOf(
                                DeleteSongPlaylistImpact("Road Trip", willBecomeEmpty = false),
                                DeleteSongPlaylistImpact("Only This Song", willBecomeEmpty = true),
                            ),
                        ),
                    ),
                    destructiveActionCount = 0,
                    onRetryPreview = {},
                )
            }
        }

        composeRule.onNodeWithText("Road Trip").assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.delete_song_playlist_will_empty, "Only This Song"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.delete_song_dialog_message),
        ).assertIsDisplayed()
    }
}
