package com.v1per.synthx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlin.system.exitProcess

/**
 * Shown for the whole of [RestoreCoordinator.Mode.RESTORE_PREPARING]: database admission is
 * closed, so nothing here may read Room, and no route out may exist.
 *
 * This is a sibling of the nav graph, not an overlay on top of it. Being the composed branch is
 * what makes it a block rather than a cover — [NormalShell] is not in the composition at all, so
 * no destination, ViewModel or repository Flow behind it can be created or resubscribed.
 */
@Composable
internal fun RestoreMaintenanceSurface() {
    // The nav graph is gone, so there is nothing left to pop — this stops back from finishing
    // the Activity out from under an in-flight restore instead.
    BackHandler {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .testTag(ShellTestTags.RestoreMaintenanceSurface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // TextSecondary, not Accent: the neon is reserved for the five documented contexts.
            CircularProgressIndicator(color = TextSecondary, strokeWidth = Strokes.ProgressRing)
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.restore_maintenance_title),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.restore_maintenance_message),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
            )
        }
    }
}

/** Restart-required and emergency dialog shown inside the shell; its only action is Close. */
@Composable
internal fun RestoreTerminalSurface(restoreTerminal: RestoreCoordinator.Terminal, diagnosticOperationId: String?) {
    val diagnosticId = diagnosticOperationId ?: stringResource(R.string.restore_terminal_diagnostic_unavailable)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(restoreTerminalTitle(restoreTerminal)) },
        text = { Text(restoreTerminalMessage(restoreTerminal, diagnosticId)) },
        confirmButton = {
            TextButton(
                onClick = { exitProcess(0) },
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** Dialog title shared by every restart-required and emergency surface. */
@Composable
internal fun restoreTerminalTitle(restoreTerminal: RestoreCoordinator.Terminal): String = stringResource(
    if (restoreTerminal == RestoreCoordinator.Terminal.EMERGENCY) {
        R.string.restore_terminal_title_emergency
    } else {
        R.string.restore_terminal_title_restart
    },
)

/** Dialog body shared by every restart-required and emergency surface; empty for NORMAL. */
@Composable
internal fun restoreTerminalMessage(restoreTerminal: RestoreCoordinator.Terminal, diagnosticId: String): String =
    when (restoreTerminal) {
        RestoreCoordinator.Terminal.RESTART_REQUIRED -> stringResource(R.string.restore_terminal_message_restart)

        RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED ->
            stringResource(R.string.restore_terminal_message_rolled_back)

        RestoreCoordinator.Terminal.EMERGENCY ->
            stringResource(R.string.restore_terminal_message_emergency, diagnosticId)

        RestoreCoordinator.Terminal.NORMAL -> ""
    }
