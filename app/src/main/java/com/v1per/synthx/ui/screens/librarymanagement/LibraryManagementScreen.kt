package com.v1per.synthx.ui.screens.librarymanagement

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

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

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()
        LibraryManagementScreenContent(
            folderPath = folderUri?.toString(),
            folderError = folderError,
            rescanStatus = rescanStatus,
            isScanning = isScanning,
            scanProgress = scanProgress,
            missingSongCount = missingSongCount,
            backupFolderPath = backupFolderUri?.toString(),
            backupStatus = backupStatus,
            onPickFolder = { folderPicker.launch(null) },
            onRescan = viewModel::rescanLibrary,
            onCancelScan = viewModel::cancelScan,
            onIssuesClick = onIssuesClick,
            onPickBackupFolder = { backupFolderPicker.launch(null) },
            onBackupNow = viewModel::createManualBackup,
            onRestoreClick = onRestoreClick,
        )
    }
}

@Composable
private fun LibraryManagementScreenContent(
    folderPath: String?,
    folderError: String?,
    rescanStatus: String?,
    isScanning: Boolean,
    scanProgress: ScanProgress?,
    missingSongCount: Int,
    backupFolderPath: String?,
    backupStatus: String?,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    onCancelScan: () -> Unit,
    onIssuesClick: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        MusicFolderRow(
            folderPath = folderPath,
            onClick = onPickFolder,
        )
        folderError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = Error,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.xs,
                ),
            )
        }
        RescanRow(
            status = rescanStatus,
            isScanning = isScanning,
            onClick = onRescan,
        )
        if (isScanning) {
            ScanProgressBar(progress = scanProgress)
            CancelScanRow(onClick = onCancelScan)
        }
        IssuesRow(
            count = missingSongCount,
            onClick = onIssuesClick,
        )

        HorizontalDivider(thickness = 1.dp, color = Hairline)

        TwoLineRow(
            title = stringResource(R.string.settings_backup_folder),
            subtitle = backupFolderPath ?: stringResource(R.string.settings_no_folder_selected),
            onClick = onPickBackupFolder,
        )
        TwoLineRow(
            title = stringResource(R.string.settings_backup_now),
            subtitle = backupStatus,
            onClick = onBackupNow,
        )
        TwoLineRow(
            title = stringResource(R.string.settings_restore),
            subtitle = null,
            onClick = onRestoreClick,
        )
    }
}

/** Shared shape for the menu rows: title, optional secondary line, trailing chevron. */
@Composable
private fun TwoLineRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary,
        )
    }
}

/** Badge appears only when there is something to report; the row itself is always present. */
@Composable
private fun IssuesRow(
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_issues),
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.padding(end = Spacing.sm),
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary,
        )
    }
}

/**
 * Determinate once the file list has been counted, indeterminate during the walk and the
 * missing-detection/cleanup passes. Deliberately not [com.v1per.synthx.ui.theme.Accent] —
 * the neon is reserved for the five contexts in the design system, and progress is not
 * one of them.
 */
@Composable
private fun ScanProgressBar(progress: ScanProgress?) {
    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm)
        .clip(MaterialTheme.shapes.small)

    if (progress != null && progress.isDeterminate) {
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = modifier,
            color = TextPrimary,
            trackColor = Hairline,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier,
            color = TextPrimary,
            trackColor = Hairline,
        )
    }
}

@Composable
private fun CancelScanRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.action_cancel),
            style = MaterialTheme.typography.bodyLarge,
            color = Error,
        )
    }
}

@Composable
private fun MusicFolderRow(
    folderPath: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_music_folder),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = folderPath ?: stringResource(R.string.settings_no_folder_selected),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary,
        )
    }
}

@Composable
private fun RescanRow(
    status: String?,
    isScanning: Boolean,
    onClick: () -> Unit,
) {
    Row(
        // Disabled while a scan is in progress — the status line reports progress
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isScanning, onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_rescan_library),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            if (status != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            tint = TextTertiary,
        )
    }
}

@Preview(name = "Library Management — folder selected", showBackground = true)
@Composable
private fun LibraryManagementWithFolderPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryManagementScreenContent(
                folderPath = "content://com.android.externalstorage/tree/primary:Music",
                folderError = null,
                rescanStatus = "Done — 12 added, 1 updated, 0 missing",
                isScanning = false,
                scanProgress = null,
                missingSongCount = 3,
                backupFolderPath = null,
                backupStatus = null,
                onPickFolder = {},
                onRescan = {},
                onCancelScan = {},
                onIssuesClick = {},
                onPickBackupFolder = {},
                onBackupNow = {},
                onRestoreClick = {},
            )
        }
    }
}

@Preview(name = "Library Management — no folder", showBackground = true)
@Composable
private fun LibraryManagementEmptyPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryManagementScreenContent(
                folderPath = null,
                folderError = null,
                rescanStatus = null,
                isScanning = false,
                scanProgress = null,
                missingSongCount = 0,
                backupFolderPath = null,
                backupStatus = null,
                onPickFolder = {},
                onRescan = {},
                onCancelScan = {},
                onIssuesClick = {},
                onPickBackupFolder = {},
                onBackupNow = {},
                onRestoreClick = {},
            )
        }
    }
}

@Preview(name = "Library Management — scanning", showBackground = true)
@Composable
private fun LibraryManagementScanningPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryManagementScreenContent(
                folderPath = "content://com.android.externalstorage/tree/primary:Music",
                folderError = null,
                rescanStatus = "Scanning… 240 of 512",
                isScanning = true,
                scanProgress = ScanProgress(current = 240, total = 512),
                missingSongCount = 0,
                backupFolderPath = null,
                backupStatus = null,
                onPickFolder = {},
                onRescan = {},
                onCancelScan = {},
                onIssuesClick = {},
                onPickBackupFolder = {},
                onBackupNow = {},
                onRestoreClick = {},
            )
        }
    }
}

