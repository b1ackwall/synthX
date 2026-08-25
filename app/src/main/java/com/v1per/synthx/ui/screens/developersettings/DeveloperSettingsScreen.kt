package com.v1per.synthx.ui.screens.developersettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.playlistseed.PlaylistSeedBootstrapResult
import com.v1per.synthx.ui.screens.settings.SettingsRow
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

@Composable
fun DeveloperSettingsScreen(
    topBar: @Composable () -> Unit,
    viewModel: DeveloperSettingsViewModel = viewModel(
        factory = DeveloperSettingsViewModel.Factory(LocalContext.current),
    ),
) {
    val isSeeding by viewModel.isSeeding.collectAsState()
    val seedResult by viewModel.seedResult.collectAsState()
    val removeOrganizedStatus by viewModel.removeOrganizedFromStarredStatus.collectAsState()
    val isRemovingOrganized by viewModel.isRemovingOrganizedFromStarred.collectAsState()
    var showConfirmation by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()
        SettingsRow(
            title = stringResource(R.string.settings_seed_playlists_from_manifest),
            enabled = !isSeeding,
            onClick = { showConfirmation = true },
        )
        RemoveOrganizedFromStarredSection(
            removeOrganizedStatus = removeOrganizedStatus,
            isRemovingOrganized = isRemovingOrganized,
            onRemoveOrganizedFromStarred = viewModel::removeOrganizedFromStarred,
        )
    }

    if (showConfirmation) {
        ConfirmPlaylistSeedDialog(
            onConfirm = {
                showConfirmation = false
                viewModel.seedPlaylistsFromManifest()
            },
            onDismiss = { showConfirmation = false },
        )
    }

    seedResult?.let { result ->
        PlaylistSeedResultDialog(
            result = result,
            onDismiss = viewModel::dismissSeedResult,
        )
    }
}

@Composable
private fun ConfirmPlaylistSeedDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_seed_confirm_title)) },
        text = { Text(stringResource(R.string.playlist_seed_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.playlist_seed_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Composable
private fun PlaylistSeedResultDialog(
    result: PlaylistSeedBootstrapResult,
    onDismiss: () -> Unit,
) {
    val title: String
    val message: String
    when (result) {
        is PlaylistSeedBootstrapResult.Complete -> {
            title = stringResource(R.string.playlist_seed_complete_title)
            message = stringResource(
                R.string.playlist_seed_complete_message,
                result.playlistsCreated,
                result.membershipsRestored,
                result.totalMemberships,
                result.uniqueMissingSongs,
            )
        }
        PlaylistSeedBootstrapResult.PlaylistsAlreadyExist -> {
            title = stringResource(R.string.playlist_seed_cancelled_title)
            message = stringResource(R.string.playlist_seed_cancelled_existing)
        }
        PlaylistSeedBootstrapResult.InvalidManifest -> {
            title = stringResource(R.string.playlist_seed_failed_title)
            message = stringResource(R.string.playlist_seed_failed_invalid_manifest)
        }
        PlaylistSeedBootstrapResult.DatabaseFailure -> {
            title = stringResource(R.string.playlist_seed_failed_title)
            message = stringResource(R.string.playlist_seed_failed_database)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}
