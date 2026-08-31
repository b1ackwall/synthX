package com.v1per.synthx.ui.screens.restore

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupPreviewCounts
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary
import kotlin.system.exitProcess
import android.net.Uri
// Aliased: the Material3 `Surface` composable already owns this simple name in this file.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

@Composable
fun RestoreScreen(
    topBar: @Composable () -> Unit,
    viewModel: RestoreViewModel = viewModel(
        factory = RestoreViewModel.Factory(LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val outcome by viewModel.outcome.collectAsState()
    val preview by viewModel.preview.collectAsState()

    val isRestoring = outcome is RestoreOutcome.InProgress
    val isPreviewing = preview !is RestorePreview.Idle

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()
        RestoreContent(
            uiState = uiState,
            enabled = !isRestoring && !isPreviewing,
            onSelect = { viewModel.beginPreview(it) },
        )
    }

    when (val current = preview) {
        is RestorePreview.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = TextSecondary, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.restore_preview_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
            }
        }
        is RestorePreview.Ready -> ConfirmRestoreDialog(
            backup = current.backup,
            counts = current.counts,
            onConfirm = {
                viewModel.restore(current.backup)
                viewModel.dismissPreview()
            },
            onDismiss = { viewModel.dismissPreview() },
        )
        is RestorePreview.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(stringResource(R.string.restore_preview_error_title)) },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPreview) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            containerColor = SurfaceColor,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
        )
        RestorePreview.Idle -> Unit
    }

    when (val current = outcome) {
        is RestoreOutcome.InProgress -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = TextSecondary, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.restore_in_progress),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
            }
        }
        is RestoreOutcome.Complete -> AlertDialog(
            // Not dismissible: the DB handle is closed and every live ViewModel is stale.
            // The only correct next step is a cold start.
            onDismissRequest = {},
            title = { Text(stringResource(R.string.restore_done_title)) },
            text = { Text(stringResource(R.string.restore_done_message)) },
            confirmButton = {
                TextButton(onClick = { exitProcess(0) }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            containerColor = SurfaceColor,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
        )
        is RestoreOutcome.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissFailure,
            title = { Text(stringResource(R.string.restore_failed_title)) },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissFailure) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            containerColor = SurfaceColor,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
        )
        else -> Unit
    }
}

@Composable
private fun RestoreContent(
    uiState: UiState<List<BackupInfo>>,
    enabled: Boolean = true,
    onSelect: (BackupInfo) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> LoadingIndicator()
            is UiState.Empty -> Text(
                text = stringResource(R.string.restore_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Spacing.HorizontalPadding),
            )
            is UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Spacing.HorizontalPadding),
            )
            is UiState.Success -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.data, key = { it.databaseUri.toString() }) { backup ->
                    BackupRow(
                        backup = backup,
                        enabled = enabled && backup.restoreEligible,
                        onClick = { onSelect(backup) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupRow(
    backup: BackupInfo,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }

    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        ) {
            Text(
                text = DateUtils.getRelativeTimeSpanString(
                    backup.timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!backup.restoreEligible) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = backup.restoreIneligibleReason ?: "Restore unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = Error,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = backup.reason?.let {
                    stringResource(R.string.restore_tier_with_reason, backup.tier.folderName, it)
                } ?: backup.tier.folderName,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${backup.fileName} · ${Formatter.formatShortFileSize(context, backup.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(thickness = 1.dp, color = Hairline)
    }
}

@Composable
private fun ConfirmRestoreDialog(
    backup: BackupInfo,
    counts: BackupPreviewCounts,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.restore_confirm_message, backup.fileName))
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    stringResource(
                        R.string.restore_confirm_counts,
                        counts.playlistCount,
                        counts.songCount,
                        counts.playlistEntryCount,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.restore_confirm_action), color = Error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Preview(name = "Restore — Success", showBackground = true)
@Composable
private fun RestoreListPreview() {
    val now = System.currentTimeMillis()
    SynthXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            RestoreContent(
                uiState = UiState.Success(
                    listOf(
                        BackupInfo(
                            databaseUri = Uri.EMPTY,
                            metadataUri = null,
                            fileName = "synthx_backup_2026-08-06_143022.db",
                            tier = BackupTier.Manual,
                            timestamp = now - DateUtils.HOUR_IN_MILLIS,
                            reason = null,
                            sizeBytes = 2_400_000,
                        ),
                        BackupInfo(
                            databaseUri = Uri.parse("content://x/2"),
                            metadataUri = null,
                            fileName = "synthx_backup_2026-08-05_090114.db",
                            tier = BackupTier.Auto,
                            timestamp = now - DateUtils.DAY_IN_MILLIS,
                            reason = "delete_song",
                            sizeBytes = 2_390_000,
                        ),
                    ),
                ),
                onSelect = {},
            )
        }
    }
}

@Preview(name = "Restore — Empty", showBackground = true)
@Composable
private fun RestoreEmptyPreview() {
    SynthXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            RestoreContent(uiState = UiState.Empty, onSelect = {})
        }
    }
}
