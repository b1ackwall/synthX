package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Hairline

@Composable
internal fun BackupSection(
    backupFolderPath: String?,
    backupStatus: String?,
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
    ManagementRow(
        title = stringResource(R.string.settings_backup_now),
        subtitle = backupStatus,
        onClick = onBackupNow,
    )
    ManagementRow(
        title = stringResource(R.string.settings_restore),
        subtitle = null,
        onClick = onRestoreClick,
    )
}
