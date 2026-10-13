package com.v1per.synthx.ui.screens.restore

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupListResult
import com.v1per.synthx.data.backup.BackupPreviewCounts
import com.v1per.synthx.data.backup.RestoreProgressStep
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.rememberNotificationPermissionGate
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlin.system.exitProcess
import android.net.Uri
import java.util.UUID
// Aliased: the Material3 `Surface` composable already owns this simple name in this file.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

@Composable
fun RestoreScreen(
    topBar: @Composable () -> Unit,
    viewModel: RestoreViewModel = viewModel(
        factory = RestoreViewModel.Factory(LocalContext.current),
    ),
    onRestoreInitiated: () -> Unit = {},
    onTerminalOutcomeShown: (UUID) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val outcome by viewModel.outcome.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val previewCountsCache by viewModel.previewCountsCache.collectAsState()
    val previewProgress by viewModel.previewProgress.collectAsState()
    val restoreProgress by viewModel.restoreProgress.collectAsState()
    val notificationGate = rememberNotificationPermissionGate()

    LaunchedEffect(outcome) {
        if (outcome is RestoreOutcome.Complete || outcome is RestoreOutcome.Failed) {
            viewModel.terminalOutcomeWasShown(onTerminalOutcomeShown)
        }
    }

    val isRestoring = outcome is RestoreOutcome.InProgress
    val isPreviewing = preview !is RestorePreview.Idle

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()
        RestoreContent(
            uiState = uiState,
            enabled = !isRestoring && !isPreviewing,
            previewCountsCache = previewCountsCache,
            onSelect = { viewModel.beginPreview(it) },
            onBack = onBack,
        )
    }

    when (val current = preview) {
        is RestorePreview.Loading -> RestoreProgressOverlay(
            step = previewProgress,
            fallbackText = stringResource(R.string.restore_preview_loading),
            // Preview staging is plain coroutine work, cancellable for its whole duration —
            // unlike an actual restore there is no uninterruptible install phase to guard.
            onCancel = viewModel::cancelPreview,
        )
        is RestorePreview.Ready -> ConfirmRestoreDialog(
            backup = current.backup,
            counts = current.counts,
            // P9-T5 / F19: a restore is the other early notification (its foreground progress),
            // and the only one a recovering user reaches before any first scan. Shared by the
            // wizard's recovery step and Settings; the "asked" flag keeps it to one prompt ever.
            onConfirm = {
                notificationGate.runAfterRationale {
                    viewModel.restore(current.backup, onInitiated = onRestoreInitiated)
                    viewModel.dismissPreview()
                }
            },
            onDismiss = { viewModel.dismissPreview() },
        )
        is RestorePreview.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(stringResource(R.string.restore_preview_error_title)) },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::dismissPreview,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) {
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
        is RestoreOutcome.InProgress -> RestoreProgressOverlay(
            step = restoreProgress,
            fallbackText = stringResource(R.string.restore_in_progress),
            // Cancel is only offered while still in the cancellable copy/validate window —
            // once the live-swap begins (INSTALLING/FINISHING) it can no longer be
            // interrupted, see RestoreTransaction.execute's NonCancellable section.
            onCancel = if (
                restoreProgress == null ||
                restoreProgress == RestoreProgressStep.COPYING ||
                restoreProgress == RestoreProgressStep.VALIDATING
            ) viewModel::cancelRestore else null,
        )
        is RestoreOutcome.Complete -> AlertDialog(
            // Not dismissible: the DB handle is closed and every live ViewModel is stale.
            // The only correct next step is a cold start.
            onDismissRequest = {},
            title = { Text(stringResource(R.string.restore_done_title)) },
            text = { Text(stringResource(R.string.restore_done_message)) },
            confirmButton = {
                TextButton(
                    onClick = { exitProcess(0) },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) {
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
                TextButton(
                    onClick = viewModel::dismissFailure,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) {
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

/**
 * R7-F17: the shared full-screen overlay for both the preview-staging and restore-in-progress
 * waits — determinate once a [RestoreProgressStep] is known, indeterminate
 * otherwise, crossfading between the two exactly like [com.v1per.synthx.ui.screens.librarymanagement.ScanSection]'s
 * `ScanProgressBar`. [onCancel] is null to hide the cancel row entirely — used once a restore
 * has passed into its uninterruptible install phase.
 */
@Composable
private fun RestoreProgressOverlay(
    step: RestoreProgressStep?,
    fallbackText: String,
    onCancel: (() -> Unit)?,
) {
    val steps = RestoreProgressStep.entries
    val stepIndex = step?.let(steps::indexOf)
    val label = step?.let { stringResource(restoreProgressLabelRes(it)) } ?: fallbackText

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Crossfade(
                targetState = stepIndex,
                animationSpec = tween(Motion.DurationFast, easing = Motion.EasingStandard),
                label = "restoreProgressMode",
            ) { index ->
                val modifier = Modifier.fillMaxWidth(0.6f).clip(MaterialTheme.shapes.small)
                if (index != null) {
                    LinearProgressIndicator(
                        progress = { (index + 1).toFloat() / steps.size },
                        modifier = modifier,
                        color = TextSecondary,
                        trackColor = Hairline,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(modifier = modifier, color = TextSecondary, trackColor = Hairline)
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (onCancel != null) {
                Spacer(modifier = Modifier.height(Spacing.md))
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = Error),
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

private fun restoreProgressLabelRes(step: RestoreProgressStep): Int = when (step) {
    RestoreProgressStep.COPYING -> R.string.restore_notification_checking
    RestoreProgressStep.VALIDATING -> R.string.restore_notification_validating
    RestoreProgressStep.INSTALLING -> R.string.restore_notification_safety_backup
    RestoreProgressStep.FINISHING -> R.string.restore_notification_restoring
}

/** Internal (not private) so instrumented tests can drive it directly with a constructed
 *  [BackupListResult], the same technique this file's own `@Preview`s already use. */
@Composable
internal fun RestoreContent(
    uiState: UiState<BackupListResult>,
    enabled: Boolean = true,
    previewCountsCache: Map<Uri, BackupPreviewCounts> = emptyMap(),
    onSelect: (BackupInfo) -> Unit,
    onBack: () -> Unit = {},
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
            is UiState.Success -> when (val result = uiState.data) {
                is BackupListResult.NoFolderConfigured -> EmptyStateMessage(
                    text = stringResource(R.string.restore_no_folder_configured),
                )
                is BackupListResult.FolderUnreachable -> EmptyStateMessage(
                    text = stringResource(R.string.restore_folder_unreachable),
                )
                is BackupListResult.Empty -> EmptyStateMessage(
                    text = stringResource(R.string.restore_empty),
                )
                is BackupListResult.Success -> {
                    val allIneligible = result.backups.isNotEmpty() &&
                        result.backups.none { it.restoreEligible }
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (allIneligible) {
                            AllIneligibleBanner(onBack = onBack)
                        }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(result.backups, key = { it.databaseUri.toString() }) { backup ->
                                BackupRow(
                                    backup = backup,
                                    enabled = enabled && backup.restoreEligible,
                                    // R7-F15: newest-first sort (see BackupManager.classifyBackupList)
                                    // makes the first item trivially "most recent" — no new data.
                                    isMostRecent = backup === result.backups.first(),
                                    counts = previewCountsCache[backup.databaseUri],
                                    onClick = { onSelect(backup) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
        )
    }
}
