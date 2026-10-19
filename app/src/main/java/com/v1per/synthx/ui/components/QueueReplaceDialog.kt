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
 * Asked before a Play or Shuffle button replaces a hand-built queue. Styled as
 * [QueuePromptDialog]. Cancel and dismiss leave the queue, the current song and the shuffle flag
 * exactly as they are.
 */
@Composable
fun QueueReplaceDialog(onReplace: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.queue_replace_prompt_title)) },
        text = { Text(stringResource(R.string.queue_replace_prompt_message)) },
        confirmButton = {
            TextButton(
                onClick = onReplace,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text(stringResource(R.string.action_replace))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
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
