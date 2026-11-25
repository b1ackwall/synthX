package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.ui.theme.Error

/**
 * Confirmation for deleting a playlist, matching [DeleteSongDialog]'s pattern — including
 * the destructive-action count, read the same way from [DestructiveActionTracker].
 */
@Composable
fun DeletePlaylistDialog(playlistName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val destructiveActionCount by DestructiveActionTracker.count.collectAsState()
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_playlist_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_playlist_dialog_message, playlistName))
                if (destructiveActionCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.destructive_action_warning_count,
                            destructiveActionCount,
                            destructiveActionCount,
                        ),
                        color = Error,
                    )
                }
            }
        },
        confirmButton = {
            SynthXDestructiveButton(
                onClick = onConfirm,
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(text = stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            SynthXSecondaryButton(
                onClick = onDismiss,
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
