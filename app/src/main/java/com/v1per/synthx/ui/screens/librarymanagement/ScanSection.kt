package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary

@Composable
internal fun ScanSection(
    status: String?,
    statusNeedsAttention: Boolean,
    isScanning: Boolean,
    progress: ScanProgress?,
    onRescan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    RescanRow(
        status = status,
        statusNeedsAttention = statusNeedsAttention,
        isScanning = isScanning,
        onClick = onRescan,
    )
    // expandVertically/shrinkVertically (not a plain fade) since the two rows occupy real
    // layout space — a bare fade would leave a blank gap/jump as they appear or disappear.
    // Same enter/exit token pairing as PlaylistListScreen's CreateGroupCard reveal.
    AnimatedVisibility(
        visible = isScanning,
        enter = expandVertically(
            animationSpec = tween(Motion.DurationNormal, easing = Motion.EasingStandard),
        ) + fadeIn(animationSpec = tween(Motion.DurationNormal, easing = Motion.EasingStandard)),
        exit = shrinkVertically(
            animationSpec = tween(Motion.DurationFast, easing = Motion.EasingAccelerate),
        ) + fadeOut(animationSpec = tween(Motion.DurationFast, easing = Motion.EasingAccelerate)),
    ) {
        Column {
            ScanProgressBar(progress = progress)
            CancelScanRow(onClick = onCancelScan)
        }
    }
}

@Composable
private fun RescanRow(status: String?, statusNeedsAttention: Boolean, isScanning: Boolean, onClick: () -> Unit) {
    // Disabled while a scan is in progress — the status line reports progress instead.
    ManagementRow(
        title = stringResource(R.string.settings_rescan_library),
        subtitle = status,
        subtitleColor = if (statusNeedsAttention) Error else null,
        enabled = !isScanning,
        // Unbounded: the high-impact status text's length varies with the backup filename
        // and item count, so no fixed line count can be relied on to never truncate it.
        subtitleMaxLines = Int.MAX_VALUE,
        onClick = onClick,
    )
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

    // Fast: swapping determinate/indeterminate representation of the same underlying bar,
    // not a content change — should read as a quick flicker-free handoff, not a reveal.
    Crossfade(
        targetState = progress != null && progress.isDeterminate,
        animationSpec = tween(Motion.DurationFast, easing = Motion.EasingStandard),
        label = "scanProgressMode",
    ) { determinate ->
        if (determinate) {
            LinearProgressIndicator(
                progress = { progress?.fraction ?: 0f },
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
