package com.v1per.synthx.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
// Aliased: the Material3 `Surface` composable owns the simple name in Compose files.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Asked when a Play Next/Add to Queue tap targets a song already sitting in the exact slot
 * that action would insert it into — see [PlaybackViewModel.playNext]/[addToQueue]'s
 * `NeedsConfirmation` result. Styled off [DeleteSongDialog]/[QueuePromptDialog] — the app's
 * existing confirmation dialogs — so all of them read as the same object.
 */
@Composable
fun PlayNextDuplicateDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    QueueDuplicateConfirmDialog(
        title = stringResource(R.string.play_next_duplicate_title),
        message = stringResource(R.string.play_next_duplicate_message),
        confirmLabel = stringResource(R.string.actions_play_next),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Same shape as [PlayNextDuplicateDialog], for the Add to Queue action's last-slot case. */
@Composable
fun AddToQueueDuplicateDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    QueueDuplicateConfirmDialog(
        title = stringResource(R.string.add_to_queue_duplicate_title),
        message = stringResource(R.string.add_to_queue_duplicate_message),
        confirmLabel = stringResource(R.string.actions_add_to_queue),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun QueueDuplicateConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}
