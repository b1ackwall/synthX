package com.v1per.synthx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant

/**
 * Minimal, non-blocking notice for P7.2 — [BackupFolderLaunchCheck] runs once per process and
 * this is its only surface. Reuses the plain Text-row shape already used for backupFolderError
 * on the Backups & Restore screen itself (see BackupSection.kt) rather than introducing a new
 * banner system; tapping it clears the flag and hands off to that screen, where Fix 2's own
 * re-check and the actual repair flow (Fix 1) live.
 */
@Composable
internal fun BackupFolderNotice(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.backup_folder_needs_attention),
            style = MaterialTheme.typography.bodySmall,
            color = Error,
        )
    }
}
