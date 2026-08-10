package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
internal fun ScanSection(
    status: String?,
    isScanning: Boolean,
    progress: ScanProgress?,
    onRescan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    RescanRow(
        status = status,
        isScanning = isScanning,
        onClick = onRescan,
    )
    if (isScanning) {
        ScanProgressBar(progress = progress)
        CancelScanRow(onClick = onCancelScan)
    }
}

@Composable
private fun RescanRow(
    status: String?,
    isScanning: Boolean,
    onClick: () -> Unit,
) {
    Row(
        // Disabled while a scan is in progress — the status line reports progress
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isScanning, onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_rescan_library),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            if (status != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            tint = TextTertiary,
        )
    }
}

/**
 * Determinate once the file list has been counted, indeterminate during the walk and the
 * missing-detection/cleanup passes. Deliberately not [com.v1per.synthx.ui.theme.Accent] —
 * the neon is reserved for the five contexts in the design system, and progress is not
 * one of them.
 */
@Composable
private fun ScanProgressBar(progress: ScanProgress?) {
    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm)
        .clip(MaterialTheme.shapes.small)

    if (progress != null && progress.isDeterminate) {
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = modifier,
            color = TextPrimary,
            trackColor = Hairline,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier,
            color = TextPrimary,
            trackColor = Hairline,
        )
    }
}

@Composable
private fun CancelScanRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.action_cancel),
            style = MaterialTheme.typography.bodyLarge,
            color = Error,
        )
    }
}
