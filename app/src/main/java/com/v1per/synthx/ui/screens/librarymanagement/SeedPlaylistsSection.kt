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
 * Temporary feature — safe to delete. Removing this file plus its four other touch
 * points fully removes "Seed Playlists (Temporary)" with no other cleanup needed:
 *   1. data/PlaylistSeeder.kt
 *   2. This file (ui/screens/librarymanagement/SeedPlaylistsSection.kt)
 *   3. The `seedPlaylists()` function (plus `_seedPlaylistsStatus`/`isSeeding` state) in
 *      LibraryManagementViewModel.kt
 *   4. The `SeedPlaylistsSection(...)` call in LibraryManagementScreenContent, and its
 *      `seedPlaylistsStatus`/`isSeeding` params (LibraryManagementScreen.kt)
 *   5. The `seedPlaylistsStatus`/`isSeeding`/`onSeedPlaylists` args added to the three
 *      `LibraryManagementScreenContent(...)` preview call sites
 *      (LibraryManagementScreenPreviews.kt)
 *
 * Mirrors [BackupSection]'s shape: a divider, a section header, and one action row
 * whose subtitle carries the last run's status. The indeterminate progress bar while
 * [isSeeding] is true reuses [ScanSection]'s bar styling (same colors, same
 * fillMaxWidth/clip shape) rather than inventing a new loading indicator.
 *
 * The "Remove organized from Starred (temporary)" row directly below is a separate,
 * independently-removable piece of debug tooling — see
 * [com.v1per.synthx.data.db.SongDao.removeOrganizedFromStarred] for its own removal
 * checklist. It's kept in this file rather than a new one so it doesn't add another file
 * to that checklist. Unlike Seed Playlists, this action is destructive-ish (unrecoverable
 * within the app) and irreversible enough to warrant a confirm dialog before running —
 * the dialog is local to this composable since nothing else needs its state.
 */
@Composable
internal fun SeedPlaylistsSection(
    seedStatus: String?,
    isSeeding: Boolean,
    onSeedPlaylists: () -> Unit,
    removeOrganizedStatus: String?,
    isRemovingOrganized: Boolean,
    onRemoveOrganizedFromStarred: () -> Unit,
) {
    HorizontalDivider(thickness = 1.dp, color = Hairline)
    SectionHeader(title = "Seed Playlists")

    ManagementRow(
        title = "Seed Playlists (Temporary)",
        subtitle = seedStatus,
        enabled = !isSeeding,
        onClick = onSeedPlaylists,
    )
    if (isSeeding) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm)
                .clip(MaterialTheme.shapes.small),
            color = TextPrimary,
            trackColor = Hairline,
        )
    }

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
