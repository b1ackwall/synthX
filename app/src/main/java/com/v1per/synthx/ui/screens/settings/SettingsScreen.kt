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
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            PreferencesManager(context),
            SongRepositoryImpl.create(context),
        ),
    )
    val folderUri by viewModel.musicFolderUri.collectAsState()
    val rescanStatus by viewModel.rescanStatus.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.saveMusicFolder(it)
        }
    }

    SettingsScreenContent(
        folderPath = folderUri?.toString(),
        rescanStatus = rescanStatus,
        onPickFolder = { folderPicker.launch(null) },
        onRescan = viewModel::rescanLibrary,
    )
}

@Composable
private fun SettingsScreenContent(
    folderPath: String?,
    rescanStatus: String?,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        MusicFolderRow(
            folderPath = folderPath,
            onClick = onPickFolder,
        )
        RescanRow(
            status = rescanStatus,
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
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = folderPath ?: "No folder selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                text = "Rescan library",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (status != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            color = MaterialTheme.colorScheme.background,
        ) {
            SettingsScreenContent(
                folderPath = "content://com.android.externalstorage/tree/primary:Music",
                rescanStatus = "Done — 12 added, 1 updated, 0 missing",
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
            color = MaterialTheme.colorScheme.background,
        ) {
            SettingsScreenContent(
                folderPath = null,
                rescanStatus = null,
                onPickFolder = {},
                onRescan = {},
            )
        }
    }
}
