package com.v1per.synthx.ui.screens.backupsandrestore

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

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
    val backupHealth by viewModel.backupHealth.collectAsState()
    val exportHealth by viewModel.exportHealth.collectAsState()
    val verificationFailure by viewModel.verificationFailure.collectAsState()
    val destructiveActionCount by viewModel.destructiveActionCount.collectAsState()
    val permissionDeniedMessage = stringResource(R.string.error_folder_permission_denied)

    val backupFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { newUri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    newUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                viewModel.saveBackupFolder(newUri)
            } catch (e: SecurityException) {
                viewModel.setBackupFolderError(permissionDeniedMessage)
            }
        }
    }

    var showMusicFolderRequiredDialog by remember { mutableStateOf(false) }

    SettingsDetailScaffold(topBar = topBar) {
        BackupsAndRestoreScreenContent(
            backupFolderPath = backupFolderUri?.let { FolderPathFormatter.format(context, it) },
            backupFolderError = backupFolderError,
            backupStatus = backupStatus,
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
        AlertDialog(
            onDismissRequest = { showMusicFolderRequiredDialog = false },
            title = { Text(stringResource(R.string.restore_music_folder_required_title)) },
            text = { Text(stringResource(R.string.restore_music_folder_required_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMusicFolderRequiredDialog = false
                        onManageLibraryClick()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
                ) {
                    Text(stringResource(R.string.restore_music_folder_required_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showMusicFolderRequiredDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = SurfaceColor,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
        )
    }
}

@Composable
internal fun BackupsAndRestoreScreenContent(
    backupFolderPath: String?,
    backupFolderError: String?,
    backupStatus: String?,
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
