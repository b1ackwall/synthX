package com.v1per.synthx.ui.components

import androidx.compose.material3.AlertDialog
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
 * Asked before a hand-built queue is thrown away. Styled off [DeleteSongDialog] — the two
 * are the app's only confirmations and should read as the same object.
 *
 * Dismissing (outside tap, back) takes neither branch: nothing is queued, nothing is
 * replaced, and whatever was playing carries on.
 */
@Composable
fun QueuePromptDialog(
    onKeep: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.queue_prompt_title)) },
        text = { Text(stringResource(R.string.queue_prompt_message)) },
        confirmButton = {
            TextButton(onClick = onKeep) {
                Text(stringResource(R.string.action_keep))
            }
        },
        dismissButton = {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.action_clear))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}
