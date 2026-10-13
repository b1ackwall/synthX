package com.v1per.synthx.ui.screens.issues

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupAttemptOutcome
import com.v1per.synthx.data.backup.ExportAttemptOutcome
import com.v1per.synthx.data.backup.VerificationAttemptOutcome
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.ui.components.DeleteSongDialog
import com.v1per.synthx.ui.components.DeleteSongPreviewState
import com.v1per.synthx.ui.components.ThreeDotMenuWithConfirm
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

/** The row body is inert — a missing song has no file to play. Actions live in the menu. */
@Composable
internal fun MissingSongRow(
    song: MissingSongInfo,
    deleteEnabled: Boolean,
    deleteInProgress: Boolean,
    deletePreview: DeleteSongPreviewState,
    onLoadDeletePreview: () -> Unit,
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
            MissingSongMenu(
                deleteEnabled = deleteEnabled,
                deleteInProgress = deleteInProgress,
                deletePreview = deletePreview,
                onLoadDeletePreview = onLoadDeletePreview,
                onDelete = onDelete,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Composable
private fun MissingSongMenu(
    deleteEnabled: Boolean,
    deleteInProgress: Boolean,
    deletePreview: DeleteSongPreviewState,
    onLoadDeletePreview: () -> Unit,
    onDelete: () -> Unit,
) {
    ThreeDotMenuWithConfirm(
        menuItems = { _, requestConfirm ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.issues_delete_song)) },
                enabled = deleteEnabled,
                onClick = {
                    onLoadDeletePreview()
                    requestConfirm()
                },
            )
        },
        confirmDialog = { onConfirm, onDismiss ->
            DeleteSongDialog(
                onConfirm = {
                    onConfirm()
                    onDelete()
                },
                onDismiss = onDismiss,
                confirmEnabled = !deleteInProgress,
                previewState = deletePreview,
                onRetryPreview = onLoadDeletePreview,
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
internal fun MissingArtRow(songIds: List<String>, onFix: () -> Unit) {
    ArtworkIssueRow(
        title = stringResource(R.string.issues_missing_art_title, songIds.size),
        subtitle = stringResource(R.string.issues_missing_art_subtitle),
        onFix = onFix,
    )
}

/** Aggregate row with an inert body and a non-destructive Fix menu. */
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
internal fun BackupUnhealthyRow(outcome: BackupAttemptOutcome, onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_backup_unhealthy_title, outcome.tier.folderName),
        subtitle = outcome.failureMessage
            ?: stringResource(R.string.issues_backup_unhealthy_subtitle_generic),
        timestamp = outcome.timestamp,
        onClick = onClick,
    )
}

@Composable
internal fun ExportUnhealthyRow(outcome: ExportAttemptOutcome, onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_export_unhealthy_title),
        subtitle = outcome.failureMessage
            ?: stringResource(R.string.issues_backup_unhealthy_subtitle_generic),
        timestamp = outcome.timestamp,
        onClick = onClick,
    )
}

@Composable
internal fun VerificationFailedRow(outcome: VerificationAttemptOutcome, onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_verification_failed_title),
        subtitle = outcome.failureMessage
            ?: stringResource(R.string.issues_backup_unhealthy_subtitle_generic),
        timestamp = outcome.timestamp,
        onClick = onClick,
    )
}

@Composable
internal fun DestructiveActionsRow(count: Int, onClick: () -> Unit) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_destructive_actions_title, count),
        subtitle = stringResource(R.string.issues_backup_unhealthy_subtitle_generic),
        timestamp = null,
        onClick = onClick,
    )
}

/** P3-T1: informational only, no click target — the cleanup it describes already happened. */
@Composable
internal fun ReconciledPendingCleanupsRow(count: Int, timestamp: Long) {
    SafetyNetIssueRow(
        title = stringResource(R.string.issues_reconciled_pending_cleanups_title, count),
        subtitle = relativeTime(timestamp).toString(),
        timestamp = timestamp,
        onClick = {},
    )
}

@Composable
internal fun BackupFolderGrantLostRow(onClick: () -> Unit) {
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
internal fun relativeTime(timestamp: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
