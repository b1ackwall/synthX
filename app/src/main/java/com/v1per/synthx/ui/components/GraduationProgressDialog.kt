package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Staged progress for [SongActionsViewModel.removeFromStarred]'s file-move-then-DB-update
 * flow. Shown for every [stage], including the terminal one. Success auto-dismisses shortly;
 * failures remain until [onDismiss] so recovery instructions cannot disappear unread.
 */
@Composable
fun GraduationProgressDialog(
    stage: GraduationStage,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.graduation_dialog_title)) },
        text = {
            Column {
                if (stage !is GraduationStage.Failed && stage != GraduationStage.Done) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(bottom = Spacing.sm)
                            .size(28.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = stage.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (stage is GraduationStage.Failed) Error else TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text(stringResource(R.string.graduation_dialog_close))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Composable
private fun GraduationStage.label(): String = when (this) {
    GraduationStage.Copying -> stringResource(R.string.graduation_stage_copying)
    GraduationStage.Verifying -> stringResource(R.string.graduation_stage_verifying)
    GraduationStage.DeletingOriginal -> stringResource(R.string.graduation_stage_deleting_original)
    GraduationStage.UpdatingDatabase -> stringResource(R.string.graduation_stage_updating_database)
    GraduationStage.Done -> stringResource(R.string.graduation_stage_done)
    // Composed in the data layer, not a string resource — same convention as
    // BackupPublicationException's messages in SafBackupPublisher.
    is GraduationStage.Failed -> reason
}
