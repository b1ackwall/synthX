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
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Picker for the second, companion-fed tree scanned on every launch. Carries its own error slot
 * rather than sharing [MusicFolderSection]'s: the most likely failure here is a rejected
 * nested-folder pick, and that message has to appear under the row the user tapped.
 *
 * [folderNotice] is the informational counterpart — "a scan is already running,
 * this folder will follow" — shown in the neutral secondary colour rather than error red. It is
 * its own parameter, fed by its own ViewModel flow, so it can be cleared by what it describes. A
 * real error always wins the slot: it is the one the user has to act on.
 */
@Composable
internal fun NewArrivalsFolderSection(
    folderPath: String?,
    folderError: String?,
    onClick: () -> Unit,
    folderNotice: String? = null,
) {
    ManagementRow(
        title = stringResource(R.string.settings_new_arrivals_folder),
        subtitle = folderPath ?: stringResource(R.string.settings_no_folder_selected),
        onClick = onClick,
    )
    val message = folderError ?: folderNotice
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (folderError != null) Error else TextSecondary,
            modifier = Modifier.padding(
                horizontal = Spacing.HorizontalPadding,
                vertical = Spacing.xs,
            ),
        )
    }
}
