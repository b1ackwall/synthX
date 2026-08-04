package com.v1per.synthx.ui.screens.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun SettingsScreen(topBar: @Composable () -> Unit) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(context),
    )
    val folderUri by viewModel.musicFolderUri.collectAsState()
    val rescanStatus by viewModel.rescanStatus.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val folderError by viewModel.folderError.collectAsState()

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
                viewModel.setFolderError(
                    "Failed to get permission for the selected folder. Please try again."
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()
        SettingsScreenContent(
            folderPath = folderUri?.toString(),
            folderError = folderError,
            rescanStatus = rescanStatus,
            isScanning = isScanning,
            onPickFolder = { folderPicker.launch(null) },
            onRescan = viewModel::rescanLibrary,
        )
    }
}

@Composable
private fun SettingsScreenContent(
    folderPath: String?,
    folderError: String?,
    rescanStatus: String?,
    isScanning: Boolean,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
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
                text = "Music folder",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = folderPath ?: "No folder selected",
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
                text = "Rescan library",
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

@Preview(name = "Settings — folder selected", showBackground = true)
@Composable
private fun SettingsScreenWithFolderPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            SettingsScreenContent(
                folderPath = "content://com.android.externalstorage/tree/primary:Music",
                folderError = null,
                rescanStatus = "Done — 12 added, 1 updated, 0 missing",
                isScanning = false,
                onPickFolder = {},
                onRescan = {},
            )
        }
    }
}

@Preview(name = "Settings — no folder", showBackground = true)
@Composable
private fun SettingsScreenEmptyPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            SettingsScreenContent(
                folderPath = null,
                folderError = null,
                rescanStatus = null,
                isScanning = false,
                onPickFolder = {},
                onRescan = {},
            )
        }
    }
}
