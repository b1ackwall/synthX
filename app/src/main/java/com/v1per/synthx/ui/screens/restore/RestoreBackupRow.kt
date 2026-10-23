package com.v1per.synthx.ui.screens.restore

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupIneligibilityReason
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupPreviewCounts
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Gaps
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
internal fun AllIneligibleBanner(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
    ) {
        Text(
            text = stringResource(R.string.restore_all_ineligible),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Button(
            onClick = onBack,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceVariant,
                contentColor = TextPrimary,
            ),
        ) {
            Text(text = stringResource(R.string.action_go_back))
        }
    }
}

private fun ineligibilityMessage(reason: BackupIneligibilityReason?): Int = when (reason) {
    is BackupIneligibilityReason.MissingMetadata -> R.string.restore_ineligible_missing_metadata
    is BackupIneligibilityReason.SizeMismatch -> R.string.restore_ineligible_size_mismatch
    is BackupIneligibilityReason.DuplicateDisplayName -> R.string.restore_ineligible_duplicate_name
    is BackupIneligibilityReason.UnsupportedSchema -> R.string.restore_ineligible_unsupported_schema
    is BackupIneligibilityReason.Unrecognized, null -> R.string.restore_ineligible_unknown
}

@Composable
internal fun BackupRow(
    backup: BackupInfo,
    enabled: Boolean = true,
    isMostRecent: Boolean = false,
    counts: BackupPreviewCounts? = null,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                // Trivial identity signal — no new data, just the position this row
                // already has in the newest-first sorted list.
                if (isMostRecent) {
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.restore_row_most_recent),
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent,
                        maxLines = 1,
                    )
                }
            }
            if (!backup.restoreEligible) {
                Spacer(modifier = Modifier.height(Gaps.TextLine))
                Text(
                    text = stringResource(ineligibilityMessage(backup.restoreIneligibleReason)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Error,
                )
            }
            Spacer(modifier = Modifier.height(Gaps.TextLine))
            Text(
                text = backup.reason?.let {
                    stringResource(R.string.restore_tier_with_reason, backup.tier.folderName, it)
                } ?: backup.tier.folderName,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(Gaps.TextLine))
            Text(
                text = "${backup.fileName} · ${Formatter.formatShortFileSize(context, backup.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Shown once known (cached from a prior preview tap-through) rather than
            // eagerly staged+counted for every row — see BackupManager.previewBackup's doc.
            if (counts != null) {
                Spacer(modifier = Modifier.height(Gaps.TextLine))
                Text(
                    text = stringResource(R.string.restore_row_counts, counts.songCount, counts.playlistCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(thickness = Strokes.Hairline, color = Hairline)
    }
}
