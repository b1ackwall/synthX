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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import com.v1per.synthx.ui.restoreTerminalMessage
import com.v1per.synthx.ui.restoreTerminalTitle
import com.v1per.synthx.ui.theme.TextPrimary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.ui.components.SynthXAlertDialog
import com.v1per.synthx.ui.components.SynthXButtonWidth
import com.v1per.synthx.ui.components.SynthXDestructiveButton
import com.v1per.synthx.ui.components.SynthXDialogButtonStack
import com.v1per.synthx.ui.components.SynthXPrimaryButton
import com.v1per.synthx.ui.components.SynthXSecondaryButton
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Spacing
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
        SynthXAlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.startup_failure_title)) },
            text = { Text(message) },
            confirmButton = {
                SynthXPrimaryButton(onClick = onRetry, width = SynthXButtonWidth.Dialog) {
                    Text(stringResource(R.string.action_retry))
                }
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
            SynthXAlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.migration_recovery_unavailable_title)) },
                text = { Text(stringResource(R.string.migration_recovery_unavailable_message)) },
                confirmButton = {
                    SynthXPrimaryButton(onClick = { exitProcess(0) }, width = SynthXButtonWidth.Dialog) {
                        Text(stringResource(R.string.action_close))
                    }
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
        SynthXAlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.migration_recovery_title)) },
            text = { Text(message) },
            confirmButton = {
                SynthXDestructiveButton(
                    enabled = !isRestoring,
                    width = SynthXButtonWidth.Dialog,
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
    val diagnosticId = RestoreCoordinator.diagnosticOperationId.value
        ?: stringResource(R.string.restore_terminal_diagnostic_unavailable)
    val noReportError = stringResource(R.string.restore_terminal_error_no_report)
    val noExporterError = stringResource(R.string.restore_terminal_error_no_exporter)
    val noBackupsError = stringResource(R.string.restore_terminal_error_no_backups)
    val readBackupsError = stringResource(R.string.restore_terminal_error_read_backups)
    val exportChooserTitle = stringResource(R.string.restore_terminal_export_chooser)
    var availableBackups by remember { mutableStateOf<List<BackupInfo>?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionRunning by remember { mutableStateOf(false) }
    val message = restoreTerminalMessage(state, diagnosticId) +
        if (state == RestoreCoordinator.Terminal.EMERGENCY) (actionError?.let { "\n\n$it" } ?: "") else ""
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SynthXAlertDialog(
            onDismissRequest = {},
            title = { Text(restoreTerminalTitle(state)) },
            text = { Text(message) },
            confirmButton = if (state == RestoreCoordinator.Terminal.EMERGENCY) {
                {
                    SynthXDialogButtonStack(
                        buttons = listOf(
                            {
                                SynthXPrimaryButton(
                                    enabled = !actionRunning,
                                    width = SynthXButtonWidth.Dialog,
                                    onClick = {
                                        actionRunning = true
                                        scope.launch {
                                            runCatching { BackupManager.create(context).listBackups().backupsOrEmpty() }
                                                .onSuccess { backups ->
                                                    availableBackups = backups.filter { it.restoreEligible }
                                                    actionError = if (availableBackups.isNullOrEmpty()) {
                                                        noBackupsError
                                                    } else {
                                                        null
                                                    }
                                                }
                                                .onFailure { actionError = it.message ?: readBackupsError }
                                            actionRunning = false
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            if (actionRunning) {
                                                R.string.restore_terminal_checking
                                            } else {
                                                R.string.restore_terminal_choose_backup
                                            },
                                        ),
                                    )
                                }
                            },
                            {
                                SynthXSecondaryButton(
                                    enabled = !actionRunning,
                                    width = SynthXButtonWidth.Dialog,
                                    onClick = {
                                        val uri = RestoreIncidentReports.shareUri(context, diagnosticId)
                                        if (uri == null) {
                                            actionError = noReportError
                                        } else {
                                            val send = Intent(Intent.ACTION_SEND)
                                                .setType("application/json")
                                                .putExtra(Intent.EXTRA_STREAM, uri)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            runCatching {
                                                context.startActivity(Intent.createChooser(send, exportChooserTitle))
                                            }.onFailure { actionError = noExporterError }
                                        }
                                    },
                                ) { Text(stringResource(R.string.restore_terminal_export_action)) }
                            },
                            {
                                SynthXSecondaryButton(onClick = { exitProcess(0) }, width = SynthXButtonWidth.Dialog) {
                                    Text(stringResource(R.string.action_close))
                                }
                            },
                        ),
                    )
                }
            } else {
                {
                    SynthXPrimaryButton(onClick = { exitProcess(0) }, width = SynthXButtonWidth.Dialog) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            },
        )
    }

    availableBackups?.takeIf { it.isNotEmpty() }?.let { backups ->
        EmergencyBackupChooserDialog(
            backups = backups,
            actionRunning = actionRunning,
            onPick = { backup ->
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
            onDismiss = { if (!actionRunning) availableBackups = null },
        )
    }
}

/**
 * The emergency screen's backup list: one row per eligible backup, named by its file. A tap on a row calls
 * [onPick] at once, with no further confirmation; Cancel, Back and an outside tap call [onDismiss]. While
 * [actionRunning], the rows and Cancel ignore taps and the rows draw at the disabled alpha.
 */
@Composable
internal fun EmergencyBackupChooserDialog(
    backups: List<BackupInfo>,
    actionRunning: Boolean,
    onPick: (BackupInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_terminal_choose_backup_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                backups.forEach { backup ->
                    BackupChoiceRow(fileName = backup.fileName, enabled = !actionRunning, onClick = { onPick(backup) })
                }
            }
        },
        confirmButton = {
            SynthXSecondaryButton(onClick = onDismiss, width = SynthXButtonWidth.Dialog, enabled = !actionRunning) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** A full-width row in the ManagementRow look: TextPrimary, at least 48dp tall, with the Accent row ripple. */
@Composable
private fun BackupChoiceRow(fileName: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.xxl)
            .alpha(if (enabled) 1f else Alpha.Disabled)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = Accent),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = fileName, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
    }
}
