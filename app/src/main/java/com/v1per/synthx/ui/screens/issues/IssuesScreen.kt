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
    val scanStatus by viewModel.scanStatus.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()

        // Outside the scrolling area below on purpose: the last-scan summary and the Scan
        // control must stay put and stay visible whether the list holds nothing or hundreds
        // of rows.
        ScanStatusBar(status = scanStatus, onScan = viewModel::startScan)

        IssuesContent(
            uiState = uiState,
            deleteEnabled = deleteSongsEnabled,
            onDelete = viewModel::deleteSong,
            onFixMissingArt = viewModel::fixMissingArt,
            onFixMissingPalette = viewModel::fixMissingPalette,
            onNavigateToBackups = onNavigateToBackups,
            modifier = Modifier.weight(1f),
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
private fun ScanStatusBar(status: IssuesScanStatus, onScan: () -> Unit) {
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
private fun IssuesContent(
    uiState: UiState<List<Issue>>,
    deleteEnabled: Boolean,
    onDelete: (String) -> Unit,
    onFixMissingArt: (List<String>) -> Unit,
    onFixMissingPalette: (List<String>) -> Unit,
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
                        is Issue.MissingArt -> MissingArtRow(
                            songIds = issue.songIds,
                            onFix = { onFixMissingArt(issue.songIds) },
                        )
                        is Issue.MissingPalette -> MissingPaletteRow(
                            songIds = issue.songIds,
                            onFix = { onFixMissingPalette(issue.songIds) },
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
    is Issue.MissingArt -> "missing_art"
    is Issue.MissingPalette -> "missing_palette"
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

/**
 * Non-destructive, unlike [MissingSongRow]'s delete — Fix only re-extracts art from files
 * already on disk, so it needs no confirmation dialog. The row body is inert, same shape as
 * [MissingSongRow]: the action lives in the menu, not the row tap.
 */
@Composable
private fun MissingArtRow(songIds: List<String>, onFix: () -> Unit) {
    ArtworkIssueRow(
        title = stringResource(R.string.issues_missing_art_title, songIds.size),
        subtitle = stringResource(R.string.issues_missing_art_subtitle),
        onFix = onFix,
    )
}

@Composable
private fun MissingPaletteRow(songIds: List<String>, onFix: () -> Unit) {
    ArtworkIssueRow(
        title = stringResource(R.string.issues_missing_palette_title, songIds.size),
        subtitle = stringResource(R.string.issues_missing_palette_subtitle),
        onFix = onFix,
    )
}

/** Both aggregates have an inert body and the same non-destructive Fix menu. */
@Composable
private fun ArtworkIssueRow(title: String, subtitle: String, onFix: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.HorizontalPadding, top = Spacing.md, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            }
            ArtworkFixMenu(onFix = onFix)
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Composable
private fun ArtworkFixMenu(onFix: () -> Unit) {
    ThreeDotMenuWithConfirm(
        menuItems = { closeMenu, _ ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.issues_fix_art)) },
                onClick = {
                    closeMenu()
                    onFix()
                },
            )
        },
        // No confirmation needed — Fix is non-destructive, so requestConfirm above is never
        // invoked and this is never shown.
        confirmDialog = { _, _ -> },
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
            Column(modifier = Modifier.fillMaxSize()) {
                ScanStatusBar(
                    status = IssuesScanStatus.Idle(
                        lastScanTimestamp = now - 2 * DateUtils.HOUR_IN_MILLIS,
                        issueCount = 7,
                    ),
                    onScan = {},
                )
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
                        Issue.MissingPalette(listOf("a", "b")),
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
                onFixMissingArt = {},
                onFixMissingPalette = {},
                onNavigateToBackups = {},
                modifier = Modifier.weight(1f),
            )
            }
        }
    }
}

/** Confirmed all-clear — note the copy differs from the scanning preview below. */
@Preview(name = "Issues — Empty", showBackground = true)
@Composable
private fun IssuesEmptyPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScanStatusBar(
                    status = IssuesScanStatus.Idle(
                        lastScanTimestamp = System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS,
                        issueCount = 0,
                    ),
                    onScan = {},
                )
                IssuesContent(
                    uiState = UiState.Empty,
                    deleteEnabled = true,
                    onDelete = {},
                    onFixMissingArt = {},
                    onFixMissingPalette = {},
                    onNavigateToBackups = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The state the old screen could not express: checking, with no claim either way yet. */
@Preview(name = "Issues — Scanning", showBackground = true)
@Composable
private fun IssuesScanningPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScanStatusBar(status = IssuesScanStatus.Scanning, onScan = {})
                IssuesContent(
                    uiState = UiState.Loading,
                    deleteEnabled = true,
                    onDelete = {},
                    onFixMissingArt = {},
                    onFixMissingPalette = {},
                    onNavigateToBackups = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
