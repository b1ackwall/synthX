package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
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
 *
 * Since complete delete, this removes the audio file from the device as well as the library row,
 * so the confirm button is gated behind typing the word "Delete" — see [TypedConfirmationField].
 * The typed text is local to the dialog and reset by its being torn down on dismissal (both call
 * sites drop it from composition rather than hiding it), so a cancelled attempt never leaves a
 * pre-satisfied gate behind for the next one.
 */
@Composable
fun DeleteSongDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    // P1-T3: cosmetic reinforcement of the caller's own in-flight guard (e.g.
    // SongActionsViewModel.deleteInProgress) — a double-tap is already prevented at the
    // ViewModel level regardless of whether this has a chance to take visible effect first.
    // Defaults to true so a future caller that doesn't have its own in-flight state isn't
    // forced to wire this up.
    confirmEnabled: Boolean = true,
) {
    val destructiveActionCount by DestructiveActionTracker.count.collectAsState()
    // The button's own label is the word to type, read from the one resource that provides both.
    val requiredWord = stringResource(R.string.action_delete)
    var typedConfirmation by remember { mutableStateOf("") }

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
                Spacer(modifier = Modifier.height(Spacing.md))
                TypedConfirmationField(
                    requiredWord = requiredWord,
                    value = typedConfirmation,
                    onValueChange = { typedConfirmation = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typedConfirmationMatches(typedConfirmation, requiredWord) && confirmEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = Error),
            ) {
                Text(text = requiredWord)
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
