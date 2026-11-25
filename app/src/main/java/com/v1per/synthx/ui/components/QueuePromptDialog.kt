package com.v1per.synthx.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R

const val QUEUE_PROMPT_DIALOG_TAG = "queue_prompt_dialog"
const val QUEUE_PROMPT_KEEP_BUTTON_TAG = "queue_prompt_keep_button"
const val QUEUE_PROMPT_CLEAR_BUTTON_TAG = "queue_prompt_clear_button"

/**
 * Asked before a hand-built queue is thrown away. Built on [SynthXAlertDialog] like every
 * confirmation in the app: Keep is the Primary confirm and Clear the Secondary choice, with no
 * Cancel.
 *
 * Dismissing (outside tap, back) takes neither branch: nothing is queued, nothing is
 * replaced, and whatever was playing carries on.
 */
@Composable
fun QueuePromptDialog(onKeep: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(QUEUE_PROMPT_DIALOG_TAG),
        title = { Text(stringResource(R.string.queue_prompt_title)) },
        text = { Text(stringResource(R.string.queue_prompt_message)) },
        confirmButton = {
            SynthXPrimaryButton(
                onClick = onKeep,
                modifier = Modifier.testTag(QUEUE_PROMPT_KEEP_BUTTON_TAG),
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.action_keep))
            }
        },
        dismissButton = {
            SynthXSecondaryButton(
                onClick = onClear,
                modifier = Modifier.testTag(QUEUE_PROMPT_CLEAR_BUTTON_TAG),
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.action_clear))
            }
        },
    )
}
