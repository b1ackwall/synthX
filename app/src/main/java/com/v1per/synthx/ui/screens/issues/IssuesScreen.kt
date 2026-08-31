package com.v1per.synthx.ui.screens.issues

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupAttemptOutcome
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.ExportAttemptOutcome
import com.v1per.synthx.data.backup.VerificationAttemptOutcome
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.DeleteSongDialog
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.ThreeDotMenuWithConfirm
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary
// Aliased: the Material3 `Surface` composable already owns this simple name in this file.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

@Composable
fun IssuesScreen(
    topBar: @Composable () -> Unit,
    onNavigateToBackups: () -> Unit = {},
    viewModel: IssuesViewModel = viewModel(
        factory = IssuesViewModel.Factory(LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val deleteSongsEnabled by viewModel.deleteSongsEnabled.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()

        IssuesContent(
            uiState = uiState,
            deleteEnabled = deleteSongsEnabled,
            onDelete = viewModel::deleteSong,
            onNavigateToBackups = onNavigateToBackups,
        )
    }
}

@Composable
private fun IssuesContent(
    uiState: UiState<List<Issue>>,
    deleteEnabled: Boolean,
    onDelete: (String) -> Unit,
    onNavigateToBackups: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> LoadingIndicator()
            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_no_issues),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = Error,
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
                            onDelete = { onDelete(issue.info.id) },
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
                        is Issue.BackupFolderGrantLost -> BackupFolderGrantLostRow(
                            onClick = onNavigateToBackups,
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
    is Issue.BackupFolderGrantLost -> "backup_folder_grant_lost"
}

/** The row body is inert — a missing song has no file to play. Actions live in the menu. */
@Composable
private fun MissingSongRow(
    song: MissingSongInfo,
    deleteEnabled: Boolean,
    onDelete: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.HorizontalPadding, top = Spacing.md, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.issues_missing_since,
                        relativeTime(song.missingSince),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MissingSongMenu(deleteEnabled = deleteEnabled, onDelete = onDelete)
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Composable
private fun MissingSongMenu(deleteEnabled: Boolean, onDelete: () -> Unit) {
    ThreeDotMenuWithConfirm(
        menuItems = { _, requestConfirm ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.issues_delete_song)) },
                enabled = deleteEnabled,
                onClick = requestConfirm,
            )
        },
        confirmDialog = { onConfirm, onDismiss ->
            DeleteSongDialog(
                onConfirm = {
                    onConfirm()
                    onDelete()
                },
                onDismiss = onDismiss,
            )
        },
    )
}

/** Shared body for the five safety-net rows: title/subtitle/optional-timestamp, tap to navigate. */
@Composable
private fun SafetyNetIssueRow(
    title: String,
    subtitle: String,
    timestamp: Long?,
    onClick: () -> Unit,
) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = Spacing.HorizontalPadding, top = Spacing.md, bottom = Spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (timestamp != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = relativeTime(timestamp).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Composable
private fun BackupUnhealthyRow(outcome: BackupAttemptOutcome, onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_backup_unhealthy_title, outcome.tier.folderName),
        subtitle = outcome.failureMessage
            ?: stringResource(R.string.issues_backup_unhealthy_subtitle_generic),
        timestamp = outcome.timestamp,
        onClick = onClick,
    )
}

@Composable
private fun ExportUnhealthyRow(outcome: ExportAttemptOutcome, onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_export_unhealthy_title),
        subtitle = outcome.failureMessage
            ?: stringResource(R.string.issues_backup_unhealthy_subtitle_generic),
        timestamp = outcome.timestamp,
        onClick = onClick,
    )
}

@Composable
private fun VerificationFailedRow(outcome: VerificationAttemptOutcome, onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_verification_failed_title),
        subtitle = outcome.failureMessage
            ?: stringResource(R.string.issues_backup_unhealthy_subtitle_generic),
        timestamp = outcome.timestamp,
        onClick = onClick,
    )
}

@Composable
private fun DestructiveActionsRow(count: Int, onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_destructive_actions_title, count),
        subtitle = stringResource(R.string.issues_backup_unhealthy_subtitle_generic),
        timestamp = null,
        onClick = onClick,
    )
}

@Composable
private fun BackupFolderGrantLostRow(onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_backup_folder_grant_lost_title),
        subtitle = stringResource(R.string.issues_backup_folder_grant_lost_subtitle),
        timestamp = null,
        onClick = onClick,
    )
}

/**
 * "3 days ago" / "5 min. ago". MINUTE_IN_MILLIS resolution means anything under a
 * minute reads as "0 min. ago" rather than a bare "now", which is fine here — a song
 * that vanished seconds ago is not a case worth special-casing.
 */
private fun relativeTime(timestamp: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )

@Preview(name = "Issues — Success", showBackground = true)
@Composable
private fun IssuesPreview() {
    val now = System.currentTimeMillis()
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            IssuesContent(
                uiState = UiState.Success(
                    data = listOf(
                        Issue.BackupFolderGrantLost,
                        Issue.BackupUnhealthy(
                            BackupAttemptOutcome(
                                timestamp = now - 30 * DateUtils.MINUTE_IN_MILLIS,
                                tier = BackupTier.Daily,
                                reason = null,
                                succeeded = false,
                                failureMessage = "No space left on backup folder",
                            ),
                        ),
                        Issue.DestructiveActionsSinceBackup(count = 4),
                        Issue.MissingSong(
                            MissingSongInfo(
                                id = "1",
                                title = "Severed Link",
                                artist = "KNTRL",
                                missingSince = now - 2 * DateUtils.HOUR_IN_MILLIS,
                            ),
                        ),
                        Issue.MissingSong(
                            MissingSongInfo(
                                id = "2",
                                title = "Null Signal",
                                artist = "v1per",
                                missingSince = now - 3 * DateUtils.DAY_IN_MILLIS,
                            ),
                        ),
                        Issue.MissingSong(
                            MissingSongInfo(
                                id = "3",
                                title = "Ghost Partition",
                                artist = "Unknown Artist",
                                missingSince = now - 21 * DateUtils.DAY_IN_MILLIS,
                            ),
                        ),
                    ),
                ),
                deleteEnabled = true,
                onDelete = {},
                onNavigateToBackups = {},
            )
        }
    }
}

@Preview(name = "Issues — Empty", showBackground = true)
@Composable
private fun IssuesEmptyPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            IssuesContent(
                uiState = UiState.Empty,
                deleteEnabled = true,
                onDelete = {},
                onNavigateToBackups = {},
            )
        }
    }
}
