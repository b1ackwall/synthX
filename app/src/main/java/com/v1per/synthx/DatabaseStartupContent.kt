package com.v1per.synthx

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.MigrationRecoveryAction
import com.v1per.synthx.data.backup.MigrationSnapshotStore
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestoreFailureFormatter
import com.v1per.synthx.data.backup.RestoreIncidentReports
import com.v1per.synthx.data.backup.backupsOrEmpty
import com.v1per.synthx.data.backup.UnsupportedLiveDatabaseVersionException
import com.v1per.synthx.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess
import java.util.UUID

/**
 * Shown when the database gate fails for any reason other than a failed post-migration check.
 * The generic branch can genuinely fire on a first run (Room creating a brand-new
 * database, the pending-cleanup reconcile, disk full), so its copy must not mention
 * restoring, upgrading or migrating — none of which has happened. The newer-database branch
 * cannot occur on a first run (there is no existing database to be newer).
 */
@Composable
internal fun DatabaseStartupFailureContent(error: Throwable, onRetry: () -> Unit) {
    val message = if (error is UnsupportedLiveDatabaseVersionException) {
        stringResource(R.string.startup_failure_message_newer_database)
    } else {
        // The exception detail is diagnostic, dynamic and deliberately not a resource.
        stringResource(R.string.startup_failure_message_generic) +
            (error.message?.takeIf(String::isNotBlank)?.let { "\n\n$it" } ?: "")
    }
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.startup_failure_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) { Text(stringResource(R.string.action_retry)) }
            },
        )
    }
}

/**
 * Shown when a migration ran but failed [com.v1per.synthx.data.backup.MigrationInvariantCheck].
 * Unlike [DatabaseStartupFailureContent]'s bare
 * Retry — which would just re-hit the same pending-recovery flag forever, see
 * [DatabaseStartupController.hasPendingMigrationRecovery] — this offers the one action that can
 * actually resolve it: restoring the internal pre-migration snapshot taken just before the
 * migration ran, then restarting so the whole attempt can be retried from a clean process.
 */
@Composable
internal fun MigrationRecoveryContent(
    applicationContext: android.content.Context,
    /**
     * False when [MigrationSnapshotStore.snapshotForCurrentVersion] found nothing to
     * restore. Offering "Restore and restart" then could only fail with "No internal
     * pre-migration snapshot is available to restore", so that state shows an honest
     * unavailable message with no restore action instead.
     */
    snapshotAvailable: Boolean,
) {
    if (!snapshotAvailable) {
        Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.migration_recovery_unavailable_title)) },
                text = { Text(stringResource(R.string.migration_recovery_unavailable_message)) },
                confirmButton = {
                    TextButton(
                        onClick = { exitProcess(0) },
                        colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                    ) { Text(stringResource(R.string.action_close)) }
                },
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    var isRestoring by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<String?>(null) }

    val message = stringResource(R.string.migration_recovery_message) +
        (
            restoreError?.let { "\n\n" + stringResource(R.string.migration_recovery_last_attempt_failed, it) }
                ?: ""
            )

    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.migration_recovery_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    enabled = !isRestoring,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                    onClick = {
                        isRestoring = true
                        scope.launch(Dispatchers.IO) {
                            // restoreFromInternalSnapshot's success path already
                            // clears migrationInvariantFailurePending via a blocking commit()
                            // (and clears the file-based validation marker with its own fsync)
                            // before returning — that synchronous commit()/fsync completion is
                            // the durability barrier this exitProcess needs. There is nothing
                            // async left in flight on this path by the time onSuccess runs.
                            MigrationRecoveryAction.restoreFromInternalSnapshot(applicationContext)
                                .onSuccess { exitProcess(0) }
                                .onFailure { error ->
                                    restoreError = error.message ?: error.toString()
                                    isRestoring = false
                                }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (isRestoring) {
                                R.string.migration_recovery_restoring
                            } else {
                                R.string.migration_recovery_restore_action
                            },
                        ),
                    )
                }
            },
        )
    }
}

@Composable
internal fun RestoreTerminalContent(state: RestoreCoordinator.Terminal) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnosticId = RestoreCoordinator.diagnosticOperationId.value ?: "unavailable"
    var availableBackups by remember { mutableStateOf<List<com.v1per.synthx.data.backup.BackupInfo>?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionRunning by remember { mutableStateOf(false) }
    val message = when (state) {
        RestoreCoordinator.Terminal.RESTART_REQUIRED -> "Restore complete. Close and reopen synthX to continue."

        RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED ->
            "Restore was rolled back safely. Close and reopen synthX to continue."

        RestoreCoordinator.Terminal.EMERGENCY ->
            "Automatic database recovery could not validate a usable database.\n\n" +
                "Normal app use is disabled to protect your data.\n\n" +
                "Diagnostic operation ID:\n$diagnosticId" +
                (actionError?.let { "\n\n$it" } ?: "")

        RestoreCoordinator.Terminal.NORMAL -> ""
    }
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    if (state ==
                        RestoreCoordinator.Terminal.EMERGENCY
                    ) {
                        "Database recovery required"
                    } else {
                        "Restart required"
                    },
                )
            },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = { exitProcess(0) },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) { Text("Close") }
            },
            dismissButton = {
                if (state == RestoreCoordinator.Terminal.EMERGENCY) {
                    Row {
                        TextButton(
                            enabled = !actionRunning,
                            onClick = {
                                val uri = RestoreIncidentReports.shareUri(context, diagnosticId)
                                if (uri == null) {
                                    actionError = "No persisted diagnostic report is available to export."
                                } else {
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("application/json")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    runCatching {
                                        context.startActivity(Intent.createChooser(send, "Export restore diagnostics"))
                                    }.onFailure { actionError = "No app could export the diagnostic report." }
                                }
                            },
                        ) { Text("Export diagnostics") }
                        TextButton(
                            enabled = !actionRunning,
                            onClick = {
                                actionRunning = true
                                scope.launch {
                                    runCatching { BackupManager.create(context).listBackups().backupsOrEmpty() }
                                        .onSuccess { backups ->
                                            availableBackups = backups.filter { it.restoreEligible }
                                            actionError = if (availableBackups.isNullOrEmpty()) {
                                                "No eligible backup is available in the selected backup folder."
                                            } else {
                                                null
                                            }
                                        }
                                        .onFailure { actionError = it.message ?: "Could not read backups." }
                                    actionRunning = false
                                }
                            },
                        ) { Text(if (actionRunning) "Checking…" else "Choose backup") }
                    }
                }
            },
        )
    }

    availableBackups?.takeIf { it.isNotEmpty() }?.let { backups ->
        AlertDialog(
            onDismissRequest = { if (!actionRunning) availableBackups = null },
            title = { Text("Choose recovery backup") },
            text = {
                Column(modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    backups.forEach { backup ->
                        TextButton(
                            enabled = !actionRunning,
                            onClick = {
                                actionRunning = true
                                scope.launch {
                                    BackupManager.create(context)
                                        .emergencyRestore(backup, UUID.randomUUID().toString())
                                        .onSuccess { exitProcess(0) }
                                        .onFailure {
                                            actionError = RestoreFailureFormatter.describe(it, diagnosticId)
                                            actionRunning = false
                                            availableBackups = null
                                        }
                                }
                            },
                        ) { Text(backup.fileName) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    enabled = !actionRunning,
                    onClick = { availableBackups = null },
                ) { Text("Cancel") }
            },
        )
    }
}
