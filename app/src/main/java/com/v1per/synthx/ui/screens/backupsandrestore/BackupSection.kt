package com.v1per.synthx.ui.screens.backupsandrestore

import android.text.format.DateUtils
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.screens.librarymanagement.ManagementRow
import com.v1per.synthx.ui.screens.librarymanagement.SectionHeader
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
internal fun BackupSection(
    backupFolderPath: String?,
    backupFolderError: String?,
    backupStatus: String?,
    isBackingUp: Boolean,
    backupHealth: BackupHealthState,
    exportHealth: ExportHealthState,
    verificationFailure: VerificationFailure?,
    destructiveActionCount: Int,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    HorizontalDivider(thickness = 1.dp, color = Hairline)
    SectionHeader(title = stringResource(R.string.section_backups_and_restore))

    ManagementRow(
        title = stringResource(R.string.settings_backup_folder),
        subtitle = backupFolderPath ?: stringResource(R.string.settings_no_folder_selected),
        onClick = onPickBackupFolder,
    )
    // Folder-health error (Issue 6.2 — "can I write here") stays its own line, distinct from
    // backup-outcome status below ("did the last attempt actually succeed") — the two are
    // related but answer different questions, and folding them together would hide whichever
    // one isn't currently the problem.
    backupFolderError?.let { error ->
        StatusLine(text = error, isError = true)
    }

    // R8-B07: disabled while a backup is in flight, matching RescanRow's `enabled = !isScanning`
    // pattern — the status line reports progress instead, and repeated taps can't queue up
    // multiple sequential snapshots of the same library state.
    ManagementRow(
        title = stringResource(R.string.settings_backup_now),
        subtitle = backupStatus,
        enabled = !isBackingUp,
        onClick = onBackupNow,
    )
    BackupHealthLine(backupHealth)
    verificationFailure?.let { failure ->
        StatusLine(
            text = stringResource(
                R.string.backup_verification_failed,
                DateUtils.getRelativeTimeSpanString(failure.timestamp),
                failure.message ?: stringResource(R.string.scan_status_unknown_error),
            ),
            isError = true,
        )
    }
    ExportHealthLine(exportHealth)
    // Only surfaced once there's actually something to warn about (N13/P3.3) — a "0 destructive
    // actions" line every time the user opens this screen would be noise, not a warning.
    if (destructiveActionCount > 0) {
        StatusLine(
            text = stringResource(R.string.destructive_action_warning, destructiveActionCount),
            isError = true,
        )
    }

    ManagementRow(
        title = stringResource(R.string.settings_restore),
        subtitle = null,
        onClick = onRestoreClick,
    )
}

@Composable
private fun BackupHealthLine(state: BackupHealthState) {
    when (state) {
        BackupHealthState.Unknown -> StatusLine(
            text = stringResource(R.string.backup_health_unknown),
            isError = false,
        )
        is BackupHealthState.Succeeded -> StatusLine(
            text = stringResource(
                R.string.backup_health_succeeded,
                DateUtils.getRelativeTimeSpanString(state.timestamp),
                state.tier.name,
            ),
            isError = false,
        )
        is BackupHealthState.Failed -> StatusLine(
            text = stringResource(
                R.string.backup_health_failed,
                DateUtils.getRelativeTimeSpanString(state.timestamp),
                state.message ?: stringResource(R.string.scan_status_unknown_error),
            ),
            isError = true,
        )
    }
}

@Composable
private fun ExportHealthLine(state: ExportHealthState) {
    when (state) {
        // Nothing exported yet is not worth a line — unlike backup health, there is no
        // "you should have one of these by now" expectation for a library with no playlists.
        ExportHealthState.Unknown -> Unit
        is ExportHealthState.Succeeded -> StatusLine(
            text = stringResource(
                R.string.export_health_succeeded,
                DateUtils.getRelativeTimeSpanString(state.timestamp),
            ),
            isError = false,
        )
        is ExportHealthState.Failed -> StatusLine(
            text = stringResource(
                R.string.export_health_failed,
                DateUtils.getRelativeTimeSpanString(state.timestamp),
                state.message ?: stringResource(R.string.scan_status_unknown_error),
            ),
            isError = true,
        )
    }
}

@Composable
private fun StatusLine(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) Error else TextSecondary,
        modifier = Modifier.padding(
            horizontal = Spacing.HorizontalPadding,
            vertical = Spacing.xs,
        ),
    )
}
