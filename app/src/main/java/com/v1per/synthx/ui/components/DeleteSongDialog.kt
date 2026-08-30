package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
// Aliased: the Material3 `Surface` composable owns the simple name in Compose files.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Confirmation for the one irreversible action in the app. Shared by the Issues screen
 * and the song actions sheet so both describe the same consequences in the same words.
 *
 * P3.3: reads [DestructiveActionTracker] directly, the same way MainScaffold reads
 * BackupFolderLaunchCheck.grantLost — a process-wide StateFlow, not plumbed through every
 * caller's own ViewModel just to reach this dialog.
 */
@Composable
fun DeleteSongDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val destructiveActionCount by DestructiveActionTracker.count.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_song_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_song_dialog_message))
                if (destructiveActionCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.destructive_action_warning,
                            destructiveActionCount,
                        ),
                        color = Error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = Error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}
