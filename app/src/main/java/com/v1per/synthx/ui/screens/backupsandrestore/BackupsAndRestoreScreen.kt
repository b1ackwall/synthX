package com.v1per.synthx.ui.screens.backupsandrestore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.SettingsDetailScaffold
import com.v1per.synthx.ui.screens.librarymanagement.FolderPathFormatter
import com.v1per.synthx.ui.screens.librarymanagement.handleFolderPick
import com.v1per.synthx.ui.screens.librarymanagement.takeFolderGrant
import com.v1per.synthx.ui.components.SynthXAlertDialog
import com.v1per.synthx.ui.components.SynthXButtonWidth
import com.v1per.synthx.ui.components.SynthXPrimaryButton
import com.v1per.synthx.ui.components.SynthXSecondaryButton

@Composable
fun BackupsAndRestoreScreen(
    topBar: @Composable () -> Unit,
    onRestoreClick: () -> Unit,
    onManageLibraryClick: () -> Unit,
    viewModel: BackupsAndRestoreViewModel = viewModel(
        factory = BackupsAndRestoreViewModel.Factory(LocalContext.current),
    ),
) {
    val context = LocalContext.current
    val backupFolderUri by viewModel.backupFolderUri.collectAsState()
    val backupFolderError by viewModel.backupFolderError.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()
    val backupHealth by viewModel.backupHealth.collectAsState()
    val exportHealth by viewModel.exportHealth.collectAsState()
    val verificationFailure by viewModel.verificationFailure.collectAsState()
    val destructiveActionCount by viewModel.destructiveActionCount.collectAsState()
    val grantRefusedMessage = stringResource(R.string.error_folder_grant_refused)

    val backupFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        handleFolderPick(
            picked = uri,
            takePermission = context::takeFolderGrant,
            onPicked = { viewModel.saveBackupFolder(it) },
            onGrantRefused = { viewModel.setBackupFolderError(grantRefusedMessage) },
        )
    }

    var showMusicFolderRequiredDialog by remember { mutableStateOf(false) }

    SettingsDetailScaffold(topBar = topBar) {
        BackupsAndRestoreScreenContent(
            backupFolderPath = backupFolderUri?.let { FolderPathFormatter.format(context, it) },
            backupFolderError = backupFolderError,
            backupStatus = backupStatus,
            isBackingUp = isBackingUp,
            backupHealth = backupHealth,
            exportHealth = exportHealth,
            verificationFailure = verificationFailure,
            destructiveActionCount = destructiveActionCount,
            onPickBackupFolder = { backupFolderPicker.launch(null) },
            onBackupNow = viewModel::createManualBackup,
            // Gated here rather than inside BackupsAndRestoreScreenContent/BackupSection: a
            // restored library needs a configured root music folder to find any songs in
            // (and after a reinstall, the old folder's SAF grant is gone regardless), so this
            // blocks navigation into RestoreScreen entirely rather than letting the user pick
            // a backup only to find out afterward. RestoreViewModel.restore() carries the same
            // check as a backstop for any other path that could reach it.
            onRestoreClick = {
                if (viewModel.musicFolderConfigured) onRestoreClick() else showMusicFolderRequiredDialog = true
            },
        )
    }

    if (showMusicFolderRequiredDialog) {
        MusicFolderRequiredDialog(
            onSelectFolder = {
                showMusicFolderRequiredDialog = false
                onManageLibraryClick()
            },
            onDismiss = { showMusicFolderRequiredDialog = false },
        )
    }
}

/** Asked when Restore is tapped before a music folder is set: Select folder opens Library Management. */
@Composable
internal fun MusicFolderRequiredDialog(onSelectFolder: () -> Unit, onDismiss: () -> Unit) {
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_music_folder_required_title)) },
        text = { Text(stringResource(R.string.restore_music_folder_required_message)) },
        confirmButton = {
            SynthXPrimaryButton(onClick = onSelectFolder, width = SynthXButtonWidth.Dialog) {
                Text(stringResource(R.string.restore_music_folder_required_action))
            }
        },
        dismissButton = {
            SynthXSecondaryButton(onClick = onDismiss, width = SynthXButtonWidth.Dialog) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun BackupsAndRestoreScreenContent(
    backupFolderPath: String?,
    backupFolderError: String?,
    backupStatus: String?,
    isBackingUp: Boolean = false,
    backupHealth: BackupHealthState = BackupHealthState.Unknown,
    exportHealth: ExportHealthState = ExportHealthState.Unknown,
    verificationFailure: VerificationFailure? = null,
    destructiveActionCount: Int = 0,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    Column {
        BackupSection(
            backupFolderPath = backupFolderPath,
            backupFolderError = backupFolderError,
            backupStatus = backupStatus,
            isBackingUp = isBackingUp,
            backupHealth = backupHealth,
            exportHealth = exportHealth,
            verificationFailure = verificationFailure,
            destructiveActionCount = destructiveActionCount,
            onPickBackupFolder = onPickBackupFolder,
            onBackupNow = onBackupNow,
            onRestoreClick = onRestoreClick,
        )
    }
}
