package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
// Aliased: the Material3 `Surface` composable owns the simple name in Compose files.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Confirmation for deleting a playlist, matching [DeleteSongDialog]'s pattern — including
 * the destructive-action count, read the same way from [DestructiveActionTracker].
 */
@Composable
fun DeletePlaylistDialog(playlistName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val destructiveActionCount by DestructiveActionTracker.count.collectAsState()
    AlertDialog(
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
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Error),
            ) {
                Text(text = stringResource(R.string.action_delete))
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
