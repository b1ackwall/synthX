package com.v1per.synthx.ui.screens.librarymanagement

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
import com.v1per.synthx.ui.theme.Background

@Composable
fun LibraryManagementScreen(
    topBar: @Composable () -> Unit,
    onIssuesClick: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: LibraryManagementViewModel = viewModel(
        factory = LibraryManagementViewModel.Factory(context),
    )
    val folderUri by viewModel.musicFolderUri.collectAsState()
    val rescanStatus by viewModel.rescanStatus.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val folderError by viewModel.folderError.collectAsState()
    val missingSongCount by viewModel.missingSongCount.collectAsState()
    val backupFolderUri by viewModel.backupFolderUri.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val deleteSongsEnabled by viewModel.deleteSongsEnabled.collectAsState()
    val seedPlaylistsStatus by viewModel.seedPlaylistsStatus.collectAsState()
    val isSeeding by viewModel.isSeeding.collectAsState()

    // Resolved here, not in the callback below: the launcher result lambda is not a
    // composable scope, so stringResource cannot be called from inside it.
    val permissionDeniedMessage = stringResource(R.string.error_folder_permission_denied)

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { newUri ->
            // Release the old grant before taking the new one — grants are capped
            // per app, and nothing else ever releases them.
            val oldUri = viewModel.musicFolderUri.value
            if (oldUri != null) {
                try {
                    context.contentResolver.releasePersistableUriPermission(
                        oldUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    // Old grant already gone — nothing to release
                }
            }
            // Take the new grant
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

    // Backups need write access, unlike the read-only music folder grant above.
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
                viewModel.setFolderError(permissionDeniedMessage)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()
        LibraryManagementScreenContent(
            folderPath = folderUri?.let { FolderPathFormatter.format(context, it) },
            folderError = folderError,
            rescanStatus = rescanStatus,
            isScanning = isScanning,
            scanProgress = scanProgress,
            missingSongCount = missingSongCount,
            backupFolderPath = backupFolderUri?.let { FolderPathFormatter.format(context, it) },
            backupStatus = backupStatus,
            deleteSongsEnabled = deleteSongsEnabled,
            seedPlaylistsStatus = seedPlaylistsStatus,
            isSeeding = isSeeding,
            onPickFolder = { folderPicker.launch(null) },
            onRescan = viewModel::rescanLibrary,
            onCancelScan = viewModel::cancelScan,
            onIssuesClick = onIssuesClick,
            onPickBackupFolder = { backupFolderPicker.launch(null) },
            onBackupNow = viewModel::createManualBackup,
            onRestoreClick = onRestoreClick,
            onToggleDeleteSongs = viewModel::setDeleteSongsEnabled,
            onSeedPlaylists = viewModel::seedPlaylists,
        )
    }
}

@Composable
internal fun LibraryManagementScreenContent(
    folderPath: String?,
    folderError: String?,
    rescanStatus: String?,
    isScanning: Boolean,
    scanProgress: ScanProgress?,
    missingSongCount: Int,
    backupFolderPath: String?,
    backupStatus: String?,
    deleteSongsEnabled: Boolean,
    seedPlaylistsStatus: String?,
    isSeeding: Boolean,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    onCancelScan: () -> Unit,
    onIssuesClick: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreClick: () -> Unit,
    onToggleDeleteSongs: (Boolean) -> Unit,
    onSeedPlaylists: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader(title = stringResource(R.string.section_music_library))
        MusicFolderSection(
            folderPath = folderPath,
            folderError = folderError,
            onClick = onPickFolder,
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

        // Temporary feature — see SeedPlaylistsSection.kt's doc comment for the full
        // removal checklist.
        SeedPlaylistsSection(
            seedStatus = seedPlaylistsStatus,
            isSeeding = isSeeding,
            onSeedPlaylists = onSeedPlaylists,
        )

        BackupSection(
            backupFolderPath = backupFolderPath,
            backupStatus = backupStatus,
            onPickBackupFolder = onPickBackupFolder,
            onBackupNow = onBackupNow,
            onRestoreClick = onRestoreClick,
        )
    }
}
