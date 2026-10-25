package com.v1per.synthx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.screens.restore.RestoreAppStatus
import com.v1per.synthx.ui.screens.restore.RestoreStatusViewModel
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary

@Composable
internal fun RestoreObservedStatusHost(
    status: RestoreAppStatus,
    routeOwnsRestoreOutcome: Boolean,
    viewModel: RestoreStatusViewModel?,
) {
    if (status is RestoreAppStatus.WaitingForStorage) RestorePendingBanner()

    val terminal = status as? RestoreAppStatus.Terminal
    if (terminal != null && !routeOwnsRestoreOutcome) {
        RestoreObservedOutcomeDialog(
            terminal = terminal,
            onDismiss = {
                viewModel?.terminalWasShown(terminal.requestId)
                viewModel?.dismissTerminal(terminal.requestId)
            },
        )
    }
}

@Composable
private fun RestorePendingBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(SurfaceVariant)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm)
            .testTag(ShellTestTags.RestorePending),
    ) {
        Text(
            text = stringResource(R.string.restore_pending_storage),
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
        )
    }
}

@Composable
private fun RestoreObservedOutcomeDialog(terminal: RestoreAppStatus.Terminal, onDismiss: () -> Unit) {
    val succeeded = terminal is RestoreAppStatus.Succeeded
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(stringResource(if (succeeded) R.string.restore_done_title else R.string.restore_failed_title))
        },
        text = {
            Text(
                if (terminal is RestoreAppStatus.Failed) {
                    terminal.message
                } else {
                    stringResource(R.string.restore_done_message)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}
