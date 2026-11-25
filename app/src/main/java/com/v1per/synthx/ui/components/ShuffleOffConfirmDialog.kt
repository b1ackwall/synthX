package com.v1per.synthx.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.playback.PlaybackViewModel

const val SHUFFLE_OFF_CONFIRM_DIALOG_TAG = "shuffle_off_confirm_dialog"
const val SHUFFLE_OFF_CONFIRM_BUTTON_TAG = "shuffle_off_confirm_button"
const val SHUFFLE_OFF_DISMISS_BUTTON_TAG = "shuffle_off_dismiss_button"

/**
 * Asked before shuffle off puts the songs the user moved in a shuffled queue back in their
 * original order. Styled as [QueueReplaceDialog]. Dismiss and Back keep shuffle on and leave the
 * reordered queue exactly as it is.
 */
@Composable
fun ShuffleOffConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SHUFFLE_OFF_CONFIRM_DIALOG_TAG),
        title = { Text(stringResource(R.string.shuffle_off_confirm_title)) },
        text = { Text(stringResource(R.string.shuffle_off_confirm_message)) },
        confirmButton = {
            SynthXPrimaryButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(SHUFFLE_OFF_CONFIRM_BUTTON_TAG),
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.shuffle_off_confirm_action))
            }
        },
        dismissButton = {
            SynthXSecondaryButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(SHUFFLE_OFF_DISMISS_BUTTON_TAG),
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.shuffle_off_keep_action))
            }
        },
    )
}

/**
 * Shows [ShuffleOffConfirmDialog] while [playbackViewModel] holds a pending shuffle-off question
 * ([PlaybackViewModel.shuffleOffConfirmation]) and routes its answers back.
 */
@Composable
fun ShuffleOffConfirmationHost(playbackViewModel: PlaybackViewModel) {
    val pending by playbackViewModel.shuffleOffConfirmation.collectAsState()
    if (!pending) return
    ShuffleOffConfirmDialog(
        onConfirm = playbackViewModel::confirmShuffleOff,
        onDismiss = playbackViewModel::dismissShuffleOffConfirmation,
    )
}
