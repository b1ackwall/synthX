package com.v1per.synthx.ui.screens.developersettings

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
import com.v1per.synthx.data.playlistseed.PlaylistSeedBootstrapResult
import com.v1per.synthx.data.playlistseed.PlaylistSeedManifestRegenerationResult
import com.v1per.synthx.ui.components.SettingsDetailScaffold
import com.v1per.synthx.ui.screens.settings.SettingsRow
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
    val isRegeneratingSeedManifest by viewModel.isRegeneratingSeedManifest.collectAsState()
    val regenerateSeedManifestResult by viewModel.regenerateSeedManifestResult.collectAsState()
    val rootOrganizationCheckStatus by viewModel.rootOrganizationCheckStatus.collectAsState()
    val isCheckingRootOrganization by viewModel.isCheckingRootOrganization.collectAsState()
    val rootOrganizationCheckResult by viewModel.rootOrganizationCheckResult.collectAsState()
    var showConfirmation by remember { mutableStateOf(false) }
    var showRegenerateConfirmation by remember { mutableStateOf(false) }

    SettingsDetailScaffold(topBar = topBar) {
        SettingsRow(
            title = stringResource(R.string.settings_seed_playlists_from_manifest),
            enabled = !isSeeding,
            onClick = { showConfirmation = true },
        )
        SettingsRow(
            title = stringResource(R.string.settings_regenerate_seed_manifest),
            enabled = !isRegeneratingSeedManifest,
            onClick = { showRegenerateConfirmation = true },
        )
        VerifyRootOrganizationSection(
            checkStatus = rootOrganizationCheckStatus,
            isChecking = isCheckingRootOrganization,
            result = rootOrganizationCheckResult,
            onCheckRootLibraryOrganization = viewModel::checkRootLibraryOrganization,
            onDismissResult = viewModel::dismissRootOrganizationCheckResult,
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

    if (showRegenerateConfirmation) {
        ConfirmRegenerateSeedManifestDialog(
            onConfirm = {
                showRegenerateConfirmation = false
                viewModel.regenerateSeedManifest()
            },
            onDismiss = { showRegenerateConfirmation = false },
        )
    }

    regenerateSeedManifestResult?.let { result ->
        RegenerateSeedManifestResultDialog(
            result = result,
            onDismiss = viewModel::dismissRegenerateSeedManifestResult,
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
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text(stringResource(R.string.playlist_seed_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
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
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

@Composable
private fun ConfirmRegenerateSeedManifestDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.seed_manifest_regenerate_confirm_title)) },
        text = { Text(stringResource(R.string.seed_manifest_regenerate_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text(stringResource(R.string.seed_manifest_regenerate_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
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

@Composable
private fun RegenerateSeedManifestResultDialog(
    result: PlaylistSeedManifestRegenerationResult,
    onDismiss: () -> Unit,
) {
    val title: String
    val message: String
    when (result) {
        is PlaylistSeedManifestRegenerationResult.Success -> {
            title = stringResource(R.string.seed_manifest_regenerate_success_title)
            message = stringResource(
                R.string.seed_manifest_regenerate_success_message,
                result.fileName,
            )
        }
        PlaylistSeedManifestRegenerationResult.NoBackupFolderConfigured -> {
            title = stringResource(R.string.seed_manifest_regenerate_failed_title)
            message = stringResource(R.string.seed_manifest_regenerate_failed_no_folder)
        }
        PlaylistSeedManifestRegenerationResult.GeneratedManifestInvalid -> {
            title = stringResource(R.string.seed_manifest_regenerate_failed_title)
            message = stringResource(R.string.seed_manifest_regenerate_failed_invalid)
        }
        PlaylistSeedManifestRegenerationResult.WriteFailure -> {
            title = stringResource(R.string.seed_manifest_regenerate_failed_title)
            message = stringResource(R.string.seed_manifest_regenerate_failed_write)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}
