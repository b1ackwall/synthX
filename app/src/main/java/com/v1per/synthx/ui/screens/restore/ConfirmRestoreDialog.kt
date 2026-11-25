package com.v1per.synthx.ui.screens.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupPreviewCounts
import com.v1per.synthx.ui.components.TypedConfirmationField
import com.v1per.synthx.ui.components.typedConfirmationMatches
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.components.SynthXAlertDialog
import com.v1per.synthx.ui.components.SynthXButtonWidth
import com.v1per.synthx.ui.components.SynthXDestructiveButton
import com.v1per.synthx.ui.components.SynthXSecondaryButton

/** Internal (not private) so instrumented tests can drive it directly, the same seam
 *  [RestoreContent] already uses. */
@Composable
internal fun ConfirmRestoreDialog(
    backup: BackupInfo,
    counts: BackupPreviewCounts,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Restore replaces the entire library irreversibly — a bigger-stakes action than
    // single-song delete, which already gates behind this same typed-confirmation mechanism
    // (see DeleteSongDialog). requiredWord is sourced from the confirm button's own label,
    // exactly like DeleteSongDialog, so the two can never drift apart.
    val requiredWord = stringResource(R.string.restore_confirm_action)
    var typedConfirmation by remember { mutableStateOf("") }

    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.restore_confirm_message, backup.fileName))
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    stringResource(
                        R.string.restore_confirm_counts,
                        pluralStringResource(
                            R.plurals.restore_playlist_count,
                            counts.playlistCount,
                            counts.playlistCount,
                        ),
                        pluralStringResource(R.plurals.stats_song_count, counts.songCount, counts.songCount),
                        pluralStringResource(
                            R.plurals.restore_playlist_entry_count,
                            counts.playlistEntryCount,
                            counts.playlistEntryCount,
                        ),
                    ),
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                TypedConfirmationField(
                    requiredWord = requiredWord,
                    value = typedConfirmation,
                    onValueChange = { typedConfirmation = it },
                )
            }
        },
        confirmButton = {
            SynthXDestructiveButton(
                onClick = onConfirm,
                enabled = typedConfirmationMatches(typedConfirmation, requiredWord),
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(text = requiredWord)
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
