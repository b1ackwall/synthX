package com.v1per.synthx.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R

const val PLAY_NEXT_DUPLICATE_DIALOG_TAG = "play_next_duplicate_dialog"
const val PLAY_NEXT_DUPLICATE_CONFIRM_BUTTON_TAG = "play_next_duplicate_confirm_button"
const val PLAY_NEXT_DUPLICATE_CANCEL_BUTTON_TAG = "play_next_duplicate_cancel_button"
const val ADD_TO_QUEUE_DUPLICATE_DIALOG_TAG = "add_to_queue_duplicate_dialog"
const val ADD_TO_QUEUE_DUPLICATE_CONFIRM_BUTTON_TAG = "add_to_queue_duplicate_confirm_button"
const val ADD_TO_QUEUE_DUPLICATE_CANCEL_BUTTON_TAG = "add_to_queue_duplicate_cancel_button"

/**
 * Asked when a Play Next/Add to Queue tap targets a song already sitting in the exact slot
 * that action would insert it into — see [PlaybackViewModel.playNext]/[addToQueue]'s
 * `NeedsConfirmation` result. Built on [SynthXAlertDialog] like every confirmation in the app,
 * with a Secondary Cancel beside a Primary confirm, so all of them read as the same object.
 */
@Composable
fun PlayNextDuplicateDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    QueueDuplicateConfirmDialog(
        title = stringResource(R.string.play_next_duplicate_title),
        message = stringResource(R.string.play_next_duplicate_message),
        confirmLabel = stringResource(R.string.actions_play_next),
        tags = DuplicateDialogTags(
            dialog = PLAY_NEXT_DUPLICATE_DIALOG_TAG,
            confirm = PLAY_NEXT_DUPLICATE_CONFIRM_BUTTON_TAG,
            cancel = PLAY_NEXT_DUPLICATE_CANCEL_BUTTON_TAG,
        ),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Same shape as [PlayNextDuplicateDialog], for the Add to Queue action's last-slot case. */
@Composable
fun AddToQueueDuplicateDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    QueueDuplicateConfirmDialog(
        title = stringResource(R.string.add_to_queue_duplicate_title),
        message = stringResource(R.string.add_to_queue_duplicate_message),
        confirmLabel = stringResource(R.string.actions_add_to_queue),
        tags = DuplicateDialogTags(
            dialog = ADD_TO_QUEUE_DUPLICATE_DIALOG_TAG,
            confirm = ADD_TO_QUEUE_DUPLICATE_CONFIRM_BUTTON_TAG,
            cancel = ADD_TO_QUEUE_DUPLICATE_CANCEL_BUTTON_TAG,
        ),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** The test tags of one duplicate dialog and its two buttons. */
private class DuplicateDialogTags(val dialog: String, val confirm: String, val cancel: String)

@Composable
private fun QueueDuplicateConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    tags: DuplicateDialogTags,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(tags.dialog),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            SynthXPrimaryButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(tags.confirm),
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            SynthXSecondaryButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(tags.cancel),
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
