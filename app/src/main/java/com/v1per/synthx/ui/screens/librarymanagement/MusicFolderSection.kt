package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing

@Composable
internal fun MusicFolderSection(
    folderPath: String?,
    folderError: String?,
    onClick: () -> Unit,
) {
    ManagementRow(
        title = stringResource(R.string.settings_music_folder),
        subtitle = folderPath ?: stringResource(R.string.settings_no_folder_selected),
        onClick = onClick,
    )
    folderError?.let { error ->
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
}
