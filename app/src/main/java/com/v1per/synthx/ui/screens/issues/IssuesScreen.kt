package com.v1per.synthx.ui.screens.issues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.KnownAudioRoot
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.DeleteSongPreviewState
import com.v1per.synthx.ui.components.LoadErrorText
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary
// Aliased: the Material3 `Surface` composable already owns this simple name in this file.

@Composable
fun IssuesScreen(
    topBar: @Composable () -> Unit,
    onNavigateToBackups: () -> Unit = {},
    viewModel: IssuesViewModel = viewModel(
        factory = IssuesViewModel.Factory(LocalContext.current),
    ),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val deleteSongsEnabled by viewModel.deleteSongsEnabled.collectAsState()
    val deleteInProgress by viewModel.deleteInProgress.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()
    val deletePreview by viewModel.deletePreview.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val actionFailedMessage by rememberUpdatedState(stringResource(R.string.actions_failed))
    val deleteFileRemainedMessage by rememberUpdatedState(
        stringResource(R.string.actions_delete_file_remained),
    )
    val deleteCleanupUncertainMessage by rememberUpdatedState(
        stringResource(R.string.actions_delete_cleanup_uncertain),
    )
    val musicGrantMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_music_grant_not_writable),
    )
    val newArrivalsGrantMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_new_arrivals_grant_not_writable),
    )
    val documentNotWritableMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_document_not_writable),
    )
    val identityMismatchMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_identity_mismatch),
    )
    val rootUnknownMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_root_unknown),
    )

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                IssuesActionEvent.ActionFailed -> actionFailedMessage
                is IssuesActionEvent.DeletedButFileRemained -> deleteFileRemainedMessage
                is IssuesActionEvent.DeletedButCleanupUncertain ->
                    deleteCleanupUncertainMessage
                IssuesActionEvent.AudioCleanupRetrySucceeded ->
                    context.getString(R.string.audio_cleanup_retry_succeeded)
                is IssuesActionEvent.DeleteBlocked -> when (val reason = event.reason) {
                    is DestructiveFilePreflightResult.GrantNotWritable -> when (reason.root) {
                        KnownAudioRoot.Music -> musicGrantMessage
                        KnownAudioRoot.NewArrivals -> newArrivalsGrantMessage
                    }
                    DestructiveFilePreflightResult.DocumentNotWritable -> documentNotWritableMessage
                    DestructiveFilePreflightResult.IdentityMismatch -> identityMismatchMessage
                    DestructiveFilePreflightResult.RootUnknown -> rootUnknownMessage
                }
            }
            val retryPath = when (event) {
                is IssuesActionEvent.DeletedButFileRemained -> event.filePath
                is IssuesActionEvent.DeletedButCleanupUncertain -> event.filePath
                else -> null
            }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = retryPath?.let { context.getString(R.string.action_retry) },
                duration = if (retryPath != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && retryPath != null) {
                viewModel.retryAudioFileDeletion(retryPath)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(Background)) {
            topBar()

            // Outside the scrolling area below on purpose: the last-scan summary and the Scan
            // control must stay put and stay visible whether the list holds nothing or hundreds
            // of rows.
            ScanStatusBar(status = scanStatus, onScan = viewModel::startScan)

            IssuesContent(
                uiState = uiState,
                deleteEnabled = deleteSongsEnabled,
                deleteInProgress = deleteInProgress,
                deletePreview = deletePreview,
                onLoadDeletePreview = viewModel::loadDeletePreview,
                onDelete = viewModel::deleteSong,
                onFixMissingArt = viewModel::fixMissingArt,
                onNavigateToBackups = onNavigateToBackups,
                modifier = Modifier.weight(1f),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Fixed status area: what the last scan found, or that one is running right now, plus the
 * manual trigger. Reads [IssuesScanStatus], which is derived from WorkManager rather than from
 * anything this screen started, so a scan begun by the daily trigger or on a previous visit
 * reports honestly here instead of being papered over with a stale summary.
 */
@Composable
internal fun ScanStatusBar(status: IssuesScanStatus, onScan: () -> Unit) {
    val idle = status is IssuesScanStatus.Idle
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.HorizontalPadding, top = Spacing.md, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = scanStatusText(status),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Disabled for Resolving as well as Scanning: until WorkManager has reported, a
            // scan may already be running and this would be a second, pointless request.
            Text(
                text = stringResource(R.string.issues_scan),
                style = MaterialTheme.typography.bodyLarge,
                color = if (idle) TextPrimary else TextTertiary,
                modifier = Modifier
                    .clickable(enabled = idle, onClick = onScan)
                    .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm),
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Composable
private fun scanStatusText(status: IssuesScanStatus): String = when (status) {
    IssuesScanStatus.Resolving -> stringResource(R.string.issues_scan_status_checking)
    IssuesScanStatus.Scanning -> stringResource(R.string.issues_scan_status_scanning)
    is IssuesScanStatus.Idle -> if (status.lastScanTimestamp == 0L) {
        stringResource(R.string.issues_scan_status_never)
    } else {
        stringResource(
            R.string.issues_scan_status_last,
            relativeTime(status.lastScanTimestamp).toString(),
            status.issueCount,
        )
    }
}

@Composable
internal fun IssuesContent(
    uiState: UiState<List<Issue>>,
    deleteEnabled: Boolean,
    deleteInProgress: Boolean,
    deletePreview: DeleteSongPreviewState =
        DeleteSongPreviewState.Ready(com.v1per.synthx.data.repository.DeleteSongPreview(emptyList())),
    onLoadDeletePreview: (String) -> Unit = {},
    onDelete: (String, String) -> Unit,
    onFixMissingArt: (List<String>) -> Unit,
    onNavigateToBackups: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (uiState) {
            // A scan is running (or its status is still resolving) — no rows at all, and
            // deliberately not the "No issues found" copy below: an in-progress check saying
            // "no issues" is exactly the false all-clear this screen used to show.
            is UiState.Loading -> LoadingIndicator()
            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_no_issues),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Error -> LoadErrorText(
                message = uiState.message,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.data, key = { it.rowKey() }) { issue ->
                    when (issue) {
                        is Issue.MissingSong -> MissingSongRow(
                            song = issue.info,
                            deleteEnabled = deleteEnabled,
                            deleteInProgress = deleteInProgress,
                            deletePreview = deletePreview,
                            onLoadDeletePreview = { onLoadDeletePreview(issue.info.id) },
                            onDelete = { onDelete(issue.info.id, issue.info.filePath) },
                        )
                        is Issue.BackupUnhealthy -> BackupUnhealthyRow(
                            outcome = issue.outcome,
                            onClick = onNavigateToBackups,
                        )
                        is Issue.ExportUnhealthy -> ExportUnhealthyRow(
                            outcome = issue.outcome,
                            onClick = onNavigateToBackups,
                        )
                        is Issue.VerificationFailed -> VerificationFailedRow(
                            outcome = issue.outcome,
                            onClick = onNavigateToBackups,
                        )
                        is Issue.DestructiveActionsSinceBackup -> DestructiveActionsRow(
                            count = issue.count,
                            onClick = onNavigateToBackups,
                        )
                        is Issue.ReconciledPendingCleanups -> ReconciledPendingCleanupsRow(
                            count = issue.count,
                            timestamp = issue.timestamp,
                        )
                        is Issue.BackupFolderGrantLost -> BackupFolderGrantLostRow(
                            onClick = onNavigateToBackups,
                        )
                        is Issue.MissingArt -> MissingArtRow(
                            songIds = issue.songIds,
                            onFix = { onFixMissingArt(issue.songIds) },
                        )
                    }
                }
            }
        }
    }
}

/** Stable LazyColumn key per issue — the safety-net variants have no natural id of their own. */
private fun Issue.rowKey(): String = when (this) {
    is Issue.MissingSong -> "missing_song:${info.id}"
    is Issue.BackupUnhealthy -> "backup_unhealthy:${outcome.timestamp}"
    is Issue.ExportUnhealthy -> "export_unhealthy:${outcome.timestamp}"
    is Issue.VerificationFailed -> "verification_failed:${outcome.timestamp}"
    is Issue.DestructiveActionsSinceBackup -> "destructive_actions"
    is Issue.ReconciledPendingCleanups -> "reconciled_pending_cleanups"
    is Issue.BackupFolderGrantLost -> "backup_folder_grant_lost"
    is Issue.MissingArt -> "missing_art"
}
