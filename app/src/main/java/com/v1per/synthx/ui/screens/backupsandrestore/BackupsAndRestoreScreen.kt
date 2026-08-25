package com.v1per.synthx.ui.screens.backupsandrestore

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.screens.librarymanagement.FolderPathFormatter
import com.v1per.synthx.ui.theme.Background

@Composable
fun BackupsAndRestoreScreen(
    topBar: @Composable () -> Unit,
    onRestoreClick: () -> Unit,
    viewModel: BackupsAndRestoreViewModel = viewModel(
        factory = BackupsAndRestoreViewModel.Factory(LocalContext.current),
    ),
) {
    val context = LocalContext.current
    val backupFolderUri by viewModel.backupFolderUri.collectAsState()
    val backupFolderError by viewModel.backupFolderError.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
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

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()
        BackupsAndRestoreScreenContent(
            backupFolderPath = backupFolderUri?.let { FolderPathFormatter.format(context, it) },
            backupFolderError = backupFolderError,
            backupStatus = backupStatus,
            onPickBackupFolder = { backupFolderPicker.launch(null) },
            onBackupNow = viewModel::createManualBackup,
            onRestoreClick = onRestoreClick,
        )
    }
}

@Composable
internal fun BackupsAndRestoreScreenContent(
    backupFolderPath: String?,
    backupFolderError: String?,
    backupStatus: String?,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BackupSection(
            backupFolderPath = backupFolderPath,
            backupFolderError = backupFolderError,
            backupStatus = backupStatus,
            onPickBackupFolder = onPickBackupFolder,
            onBackupNow = onBackupNow,
            onRestoreClick = onRestoreClick,
        )
    }
}
