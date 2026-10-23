package com.v1per.synthx.ui.screens.developersettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.v1per.synthx.data.scanner.RootOrganizationCheckResult
import com.v1per.synthx.ui.screens.librarymanagement.ManagementRow
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Debug tooling — read-only audit of the root music folder: walks the live filesystem and
 * reports songs not yet in a playlist ("unorganized") plus files on disk with no matching
 * library row ("unindexed"). Non-destructive: the confirm dialog here only guards
 * against an accidental tap starting a redundant walk while one may already be running.
 */
@Composable
internal fun VerifyRootOrganizationSection(
    checkStatus: String?,
    isChecking: Boolean,
    result: RootOrganizationCheckResult?,
    onCheckRootLibraryOrganization: () -> Unit,
    onDismissResult: () -> Unit,
) {
    HorizontalDivider(thickness = Strokes.Hairline, color = Hairline)

    var showConfirmDialog by remember { mutableStateOf(false) }

    ManagementRow(
        title = "Check root library organization (debug)",
        subtitle = checkStatus,
        enabled = !isChecking,
        onClick = { showConfirmDialog = true },
    )
    if (isChecking) {
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
            title = { Text("Check root library?") },
            text = {
                Text(
                    "Walk the configured music folder and report songs not yet in a " +
                        "playlist, plus any files on disk with no matching library entry. " +
                        "Read-only — nothing is changed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onCheckRootLibraryOrganization()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) {
                    Text("Check")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                ) {
                    Text("Cancel")
                }
            },
            containerColor = SurfaceColor,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
        )
    }

    if (result != null) {
        RootOrganizationResultDialog(result = result, onDismiss = onDismissResult)
    }
}

@Composable
private fun RootOrganizationResultDialog(result: RootOrganizationCheckResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Root library check") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Unorganized (${result.unorganizedCount})",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
                if (result.unorganizedSongs.isEmpty()) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                } else {
                    result.unorganizedSongs.take(10).forEach { song ->
                        Text(
                            text = "${song.title} — ${song.filePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    if (result.unorganizedCount > 10) {
                        Text(
                            text = "+ ${result.unorganizedCount - 10} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Text(
                    text = "Unindexed (${result.unindexedCount})",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
                if (result.unindexedFilePaths.isEmpty()) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                } else {
                    result.unindexedFilePaths.take(10).forEach { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    if (result.unindexedCount > 10) {
                        Text(
                            text = "+ ${result.unindexedCount - 10} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text("OK")
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}
