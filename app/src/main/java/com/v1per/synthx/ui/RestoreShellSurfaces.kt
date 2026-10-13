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
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Spacing
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
            CircularProgressIndicator(color = TextSecondary, strokeWidth = 2.dp)
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

/** Unchanged from the guard this shell has always had; only its call site moved. */
@Composable
internal fun RestoreTerminalSurface(
    restoreTerminal: RestoreCoordinator.Terminal,
    diagnosticOperationId: String?,
) {
    val message = when (restoreTerminal) {
        RestoreCoordinator.Terminal.RESTART_REQUIRED -> "Restore complete. Close and reopen synthX to continue."
        RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED -> "Restore was rolled back safely. Close and reopen synthX to continue."
        RestoreCoordinator.Terminal.EMERGENCY ->
            "Automatic database recovery could not validate a usable database.\n\n" +
                "Normal app use is disabled to protect your data.\n\n" +
                "Diagnostic operation ID:\n${diagnosticOperationId ?: "unavailable"}"
        RestoreCoordinator.Terminal.NORMAL -> ""
    }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                if (restoreTerminal == RestoreCoordinator.Terminal.EMERGENCY) {
                    "Database recovery required"
                } else {
                    "Restart required"
                }
            )
        },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = { exitProcess(0) },
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) { Text("Close") }
        },
    )
}
