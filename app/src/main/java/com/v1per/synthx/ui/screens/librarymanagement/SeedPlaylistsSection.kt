package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary

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
 */
@Composable
internal fun SeedPlaylistsSection(
    seedStatus: String?,
    isSeeding: Boolean,
    onSeedPlaylists: () -> Unit,
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
}
