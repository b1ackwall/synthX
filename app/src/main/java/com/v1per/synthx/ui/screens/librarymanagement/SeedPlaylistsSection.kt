package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Temporary, independently-removable debug tooling — see
 * [com.v1per.synthx.data.db.SongDao.removeOrganizedFromStarred] for its own removal
 * checklist. The action is irreversible enough to warrant a confirm dialog before running;
 * the dialog is local to this composable since nothing else needs its state.
 */
@Composable
internal fun SeedPlaylistsSection(
    removeOrganizedStatus: String?,
    isRemovingOrganized: Boolean,
    onRemoveOrganizedFromStarred: () -> Unit,
) {
    HorizontalDivider(thickness = 1.dp, color = Hairline)

    var showConfirmDialog by remember { mutableStateOf(false) }

    ManagementRow(
        title = "Remove organized from Starred (temporary)",
        subtitle = removeOrganizedStatus,
        enabled = !isRemovingOrganized,
        onClick = { showConfirmDialog = true },
    )
    if (isRemovingOrganized) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm)
                .clip(MaterialTheme.shapes.small),
            color = TextPrimary,
            trackColor = Hairline,
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Remove organized from Starred?") },
            text = {
                Text(
                    "Remove all songs from Starred that are already in a playlist? " +
                        "This can't be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onRemoveOrganizedFromStarred()
                    }
                ) {
                    Text(text = "Remove", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = SurfaceColor,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
        )
    }
}
