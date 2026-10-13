package com.v1per.synthx.ui.screens.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupPreviewCounts
import com.v1per.synthx.ui.components.TypedConfirmationField
import com.v1per.synthx.ui.components.typedConfirmationMatches
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/** Internal (not private) so instrumented tests can drive it directly, the same seam
 *  [RestoreContent] already uses. */
@Composable
internal fun ConfirmRestoreDialog(
    backup: BackupInfo,
    counts: BackupPreviewCounts,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // R7-F17: restore replaces the entire library irreversibly — a bigger-stakes action than
    // single-song delete, which already gates behind this same typed-confirmation mechanism
    // (see DeleteSongDialog). requiredWord is sourced from the confirm button's own label,
    // exactly like DeleteSongDialog, so the two can never drift apart.
    val requiredWord = stringResource(R.string.restore_confirm_action)
    var typedConfirmation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.restore_confirm_message, backup.fileName))
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    stringResource(
                        R.string.restore_confirm_counts,
                        counts.playlistCount,
                        counts.songCount,
                        counts.playlistEntryCount,
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
            TextButton(
                onClick = onConfirm,
                enabled = typedConfirmationMatches(typedConfirmation, requiredWord),
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
