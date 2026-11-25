package com.v1per.synthx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.DeleteSongPlaylistImpact
import com.v1per.synthx.data.repository.DeleteSongPreview
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.ui.components.SynthXButtonRole.Destructive
import com.v1per.synthx.ui.components.SynthXButtonRole.Primary
import com.v1per.synthx.ui.components.SynthXButtonRole.Secondary
import com.v1per.synthx.ui.components.SynthXButtonRole.Tertiary
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertDialogButton
import com.v1per.synthx.ui.testing.dialogButton
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens every shared-component dialog in each of its button shapes, checks each button's role, and taps
 * every button to see it reach its own callback. A dialog slot that emits no button or two would throw here.
 */
@RunWith(AndroidJUnit4::class)
class DialogSmokeInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val taps = mutableListOf<String>()

    private fun string(id: Int) = context.getString(id)

    private fun record(name: String): () -> Unit = { taps += name }

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { SynthXTheme { content() } }
        composeRule.waitForIdle()
    }

    private fun tap(label: String) {
        composeRule.dialogButton(label).performClick()
        composeRule.waitForIdle()
    }

    private fun assertTaps(vararg expected: String) = assertEquals(expected.toList(), taps)

    @Test fun deleteSongIsGatedOnTheTypedWordAndCancelIsSecondary() {
        val preview = DeleteSongPreviewState.Ready(
            DeleteSongPreview(listOf(DeleteSongPlaylistImpact("Road Trip", false))),
        )
        show { DeleteSongDialog(onConfirm = record("delete"), onDismiss = record("cancel"), previewState = preview) }
        val delete = string(R.string.action_delete)
        composeRule.assertDialogButton(delete, Destructive, enabled = false)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        tap(delete)
        composeRule.onNode(hasSetTextAction()).performTextInput(delete)
        composeRule.waitForIdle()
        composeRule.assertDialogButton(delete, Destructive)
        tap(delete)
        tap(string(R.string.action_cancel))
        assertTaps("delete", "cancel")
    }

    @Test fun deleteSongWithAFailedPreviewOffersAnInlineTertiaryRetry() {
        show {
            DeleteSongDialog(
                onConfirm = record("delete"),
                onDismiss = record("cancel"),
                previewState = DeleteSongPreviewState.Failed,
                onRetryPreview = record("retry"),
            )
        }
        composeRule.assertDialogButton(string(R.string.action_retry), Tertiary)
        composeRule.assertDialogButton(string(R.string.action_delete), Destructive, enabled = false)
        tap(string(R.string.action_retry))
        assertTaps("retry")
    }

    @Test fun deletePlaylistIsADestructivePair() {
        show {
            DeletePlaylistDialog(playlistName = "Road Trip", onConfirm = record("delete"), onDismiss = record("cancel"))
        }
        composeRule.assertDialogButton(string(R.string.action_delete), Destructive)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        tap(string(R.string.action_delete))
        tap(string(R.string.action_cancel))
        assertTaps("delete", "cancel")
    }

    @Test fun deleteGroupIsADestructivePair() {
        show { DeleteGroupDialog(groupName = "Moods", onConfirm = record("delete"), onDismiss = record("cancel")) }
        composeRule.assertDialogButton(string(R.string.action_delete), Destructive)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        tap(string(R.string.action_delete))
        tap(string(R.string.action_cancel))
        assertTaps("delete", "cancel")
    }

    @Test fun removeFromPlaylistIsAPrimaryPair() {
        show {
            RemoveFromPlaylistDialog(
                songTitle = "Song",
                playlistName = "Road Trip",
                onConfirm = record("remove"),
                onDismiss = record("cancel"),
            )
        }
        composeRule.assertDialogButton(string(R.string.remove_from_playlist_dialog_confirm), Primary)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        tap(string(R.string.remove_from_playlist_dialog_confirm))
        tap(string(R.string.action_cancel))
        assertTaps("remove", "cancel")
    }

    @Test fun shuffleOffKeepsShuffleOnAsSecondaryAndTurnsItOffAsPrimary() {
        show { ShuffleOffConfirmDialog(onConfirm = record("turn off"), onDismiss = record("keep")) }
        composeRule.assertDialogButton(string(R.string.shuffle_off_confirm_action), Primary)
        composeRule.assertDialogButton(string(R.string.shuffle_off_keep_action), Secondary)
        tap(string(R.string.shuffle_off_confirm_action))
        tap(string(R.string.shuffle_off_keep_action))
        assertTaps("turn off", "keep")
    }

    @Test fun playNextDuplicateIsAPrimaryPair() {
        show { PlayNextDuplicateDialog(onConfirm = record("play next"), onDismiss = record("cancel")) }
        composeRule.assertDialogButton(string(R.string.actions_play_next), Primary)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        tap(string(R.string.actions_play_next))
        tap(string(R.string.action_cancel))
        assertTaps("play next", "cancel")
    }

    @Test fun addToQueueDuplicateIsAPrimaryPair() {
        show { AddToQueueDuplicateDialog(onConfirm = record("add"), onDismiss = record("cancel")) }
        composeRule.assertDialogButton(string(R.string.actions_add_to_queue), Primary)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        tap(string(R.string.actions_add_to_queue))
        tap(string(R.string.action_cancel))
        assertTaps("add", "cancel")
    }

    @Test fun replaceYourQueueIsAPrimaryPair() {
        show {
            QueueReplaceDialog(
                onReplace = record("replace"),
                onCancel = record("cancel"),
                onDismiss = record("dismiss"),
            )
        }
        composeRule.assertDialogButton(string(R.string.action_replace), Primary)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        tap(string(R.string.action_replace))
        tap(string(R.string.action_cancel))
        assertTaps("replace", "cancel")
    }

    @Test fun theQueuePromptKeepsAsPrimaryAndClearsAsSecondaryWithNoCancel() {
        show { QueuePromptDialog(onKeep = record("keep"), onClear = record("clear"), onDismiss = record("dismiss")) }
        composeRule.assertDialogButton(string(R.string.action_keep), Primary)
        composeRule.assertDialogButton(string(R.string.action_clear), Secondary)
        composeRule.dialogButton(string(R.string.action_cancel)).assertDoesNotExist()
        tap(string(R.string.action_keep))
        tap(string(R.string.action_clear))
        assertTaps("keep", "clear")
    }

    @Test fun graduationShowsALonePrimaryCloseWhileItRuns() {
        show { GraduationProgressDialog(stage = GraduationStage.Copying, onDismiss = record("close")) }
        composeRule.assertDialogButton(string(R.string.graduation_dialog_close), Primary)
        tap(string(R.string.graduation_dialog_close))
        assertTaps("close")
    }

    @Test fun graduationShowsALonePrimaryCloseAfterAFailure() {
        show { GraduationProgressDialog(stage = GraduationStage.Failed("disk full"), onDismiss = record("close")) }
        composeRule.assertDialogButton(string(R.string.graduation_dialog_close), Primary)
        tap(string(R.string.graduation_dialog_close))
        assertTaps("close")
    }

    @Test fun theNotificationRationaleContinuesWithALonePrimary() {
        show { NotificationRationaleDialog(visible = true, onContinue = record("continue")) }
        composeRule.assertDialogButton(string(R.string.action_continue), Primary)
        tap(string(R.string.action_continue))
        assertTaps("continue")
    }
}
