package com.v1per.synthx.ui.screens.librarymanagement

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.SettingsDetailScaffold

@Composable
fun LibraryManagementScreen(
    topBar: @Composable () -> Unit,
    onIssuesClick: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: LibraryManagementViewModel = viewModel(
        factory = LibraryManagementViewModel.Factory(context),
    )
    val folderUri by viewModel.musicFolderUri.collectAsState()
    val rescanStatus by viewModel.rescanStatus.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val newArrivalsFolderUri by viewModel.newArrivalsFolderUri.collectAsState()
    val folderError by viewModel.folderError.collectAsState()
    val newArrivalsFolderError by viewModel.newArrivalsFolderError.collectAsState()
    val missingSongCount by viewModel.missingSongCount.collectAsState()
    val deleteSongsEnabled by viewModel.deleteSongsEnabled.collectAsState()

    // Resolved here, not in the callback below: the launcher result lambda is not a
    // composable scope, so stringResource cannot be called from inside it.
    val permissionDeniedMessage = stringResource(R.string.error_folder_permission_denied)

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { newUri ->
            // Take the new grant first. Releasing the old one is the ViewModel's job now,
            // and happens only once the pick survives nesting validation — released here it
            // would be unrecoverable on rejection, since a persistable grant can only be
            // taken from a fresh activity result.
            try {
                context.contentResolver.takePersistableUriPermission(
                    newUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                viewModel.saveMusicFolder(newUri)
            } catch (e: SecurityException) {
                viewModel.setFolderError(permissionDeniedMessage)
            }
        }
    }

    // Second read-only tree — same grant handling as the music folder picker above.
    val newArrivalsFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { newUri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    newUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                viewModel.saveNewArrivalsFolder(newUri)
            } catch (e: SecurityException) {
                viewModel.setNewArrivalsFolderError(permissionDeniedMessage)
            }
        }
    }

    SettingsDetailScaffold(topBar = topBar) {
        LibraryManagementScreenContent(
            folderPath = folderUri?.let { FolderPathFormatter.format(context, it) },
            folderError = folderError,
            newArrivalsFolderPath = newArrivalsFolderUri?.let {
                FolderPathFormatter.format(context, it)
            },
            newArrivalsFolderError = newArrivalsFolderError,
            rescanStatus = rescanStatus,
            isScanning = isScanning,
            scanProgress = scanProgress,
            missingSongCount = missingSongCount,
            deleteSongsEnabled = deleteSongsEnabled,
            onPickFolder = { folderPicker.launch(null) },
            onPickNewArrivalsFolder = { newArrivalsFolderPicker.launch(null) },
            onRescan = viewModel::rescanLibrary,
            onCancelScan = viewModel::cancelScan,
            onIssuesClick = onIssuesClick,
            onToggleDeleteSongs = viewModel::setDeleteSongsEnabled,
        )
    }
}

@Composable
internal fun LibraryManagementScreenContent(
    folderPath: String?,
    folderError: String?,
    newArrivalsFolderPath: String?,
    newArrivalsFolderError: String?,
    rescanStatus: String?,
    isScanning: Boolean,
    scanProgress: ScanProgress?,
    missingSongCount: Int,
    deleteSongsEnabled: Boolean,
    onPickFolder: () -> Unit,
    onPickNewArrivalsFolder: () -> Unit,
    onRescan: () -> Unit,
    onCancelScan: () -> Unit,
    onIssuesClick: () -> Unit,
    onToggleDeleteSongs: (Boolean) -> Unit,
) {
    Column {
        SectionHeader(title = stringResource(R.string.section_music_library))
        MusicFolderSection(
            folderPath = folderPath,
            folderError = folderError,
            onClick = onPickFolder,
        )
        NewArrivalsFolderSection(
            folderPath = newArrivalsFolderPath,
            folderError = newArrivalsFolderError,
            onClick = onPickNewArrivalsFolder,
        )
        ScanSection(
            status = rescanStatus,
            isScanning = isScanning,
            progress = scanProgress,
            onRescan = onRescan,
            onCancelScan = onCancelScan,
        )
        IssuesRow(
            count = missingSongCount,
            onClick = onIssuesClick,
        )
        DeleteSongsRow(
            enabled = deleteSongsEnabled,
            onToggle = onToggleDeleteSongs,
        )
    }
}
