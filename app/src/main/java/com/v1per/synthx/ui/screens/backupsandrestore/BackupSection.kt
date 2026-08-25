package com.v1per.synthx.ui.screens.backupsandrestore

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

@Composable
internal fun BackupSection(
    backupFolderPath: String?,
    backupFolderError: String?,
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
    backupFolderError?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = Error,
            modifier = Modifier.padding(
                horizontal = Spacing.HorizontalPadding,
                vertical = Spacing.xs,
            ),
        )
    }
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
