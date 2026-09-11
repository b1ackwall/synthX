package com.v1per.synthx.ui.screens.onboarding

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.FolderPathFormatter
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.screens.librarymanagement.ManagementRow
import com.v1per.synthx.ui.screens.librarymanagement.MusicFolderSection
import com.v1per.synthx.ui.screens.librarymanagement.NewArrivalsFolderSection
import com.v1per.synthx.ui.screens.restore.RestoreOutcome
import com.v1per.synthx.ui.screens.restore.RestorePreview
import com.v1per.synthx.ui.screens.restore.RestoreScreen
import com.v1per.synthx.ui.screens.restore.RestoreViewModel
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/** Matches FolderSetupStep's rows, optional-folder gate, scrolling and bottom Done button. */
@Composable
internal fun RecoveryFolderSetupStep(
    libraryManagementViewModel: LibraryManagementViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val musicFolderUri by libraryManagementViewModel.musicFolderUri.collectAsState()
    val folderError by libraryManagementViewModel.folderError.collectAsState()
    val newArrivalsFolderUri by libraryManagementViewModel.newArrivalsFolderUri.collectAsState()
    val newArrivalsFolderError by libraryManagementViewModel.newArrivalsFolderError.collectAsState()
    val permissionDeniedMessage = stringResource(R.string.error_folder_permission_denied)

    BackHandler(onBack = onBack)

    val musicFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { newUri ->
            try {
                // Read + write — graduation moves files into this tree. See
                // LibraryManagementScreen's music folder picker.
                context.contentResolver.takePersistableUriPermission(
                    newUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                libraryManagementViewModel.saveRecoveryMusicFolder(newUri)
            } catch (e: SecurityException) {
                libraryManagementViewModel.setFolderError(permissionDeniedMessage)
            }
        }
    }

    val newArrivalsFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { newUri ->
            try {
                // Read + write — graduation deletes the original from this tree. See
                // LibraryManagementScreen's New Arrivals picker.
                context.contentResolver.takePersistableUriPermission(
                    newUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                libraryManagementViewModel.saveRecoveryNewArrivalsFolder(newUri)
            } catch (e: SecurityException) {
                libraryManagementViewModel.setNewArrivalsFolderError(permissionDeniedMessage)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        DetailTopBar(
            title = stringResource(R.string.onboarding_recovery_folder_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.onboarding_recovery_folder_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.md,
                ),
            )
            MusicFolderSection(
                folderPath = musicFolderUri?.let { FolderPathFormatter.format(context, it) },
                folderError = folderError,
                onClick = { musicFolderPicker.launch(null) },
            )
            // Optional, skippable, exactly as on the fresh-install path.
            NewArrivalsFolderSection(
                folderPath = newArrivalsFolderUri?.let { FolderPathFormatter.format(context, it) },
                folderError = newArrivalsFolderError,
                onClick = { newArrivalsFolderPicker.launch(null) },
            )
        }

        Button(
            onClick = onDone,
            enabled = musicFolderUri != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.HorizontalPadding)
                .height(Spacing.xxl),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceVariant,
                contentColor = TextPrimary,
            ),
        ) {
            Text(
                text = stringResource(R.string.action_done),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
            )
        }
    }
}

/** Hosts the existing candidate list and preview/confirmation inside the onboarding route. */
@Composable
internal fun RecoveryBackupSelectStep(
    backupsAndRestoreViewModel: BackupsAndRestoreViewModel,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    restoreViewModel: RestoreViewModel = viewModel(
        factory = RestoreViewModel.Factory(LocalContext.current),
    ),
) {
    val context = LocalContext.current
    val backupFolderUri by backupsAndRestoreViewModel.backupFolderUri.collectAsState()
    val backupFolderError by backupsAndRestoreViewModel.backupFolderError.collectAsState()
    val preview by restoreViewModel.preview.collectAsState()
    val outcome by restoreViewModel.outcome.collectAsState()
    val permissionDeniedMessage = stringResource(R.string.error_folder_permission_denied)
    val busy = preview !is RestorePreview.Idle ||
        outcome is RestoreOutcome.InProgress || outcome is RestoreOutcome.Complete

    // Dialogs own their Back press; block leaving the step during preview loading/submission.
    val goBack: () -> Unit = { if (!busy) onBack() }
    BackHandler(onBack = goBack)

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
                backupsAndRestoreViewModel.saveBackupFolder(newUri)
            } catch (e: SecurityException) {
                backupsAndRestoreViewModel.setBackupFolderError(permissionDeniedMessage)
            }
        }
    }

    // saveBackupFolder validates asynchronously. List only after its accepted URI is published,
    // and refresh on re-entry as well, since the route-scoped RestoreViewModel is retained.
    LaunchedEffect(backupFolderUri) {
        restoreViewModel.refresh()
    }

    RestoreScreen(
        viewModel = restoreViewModel,
        // Submission is the last synchronous navigation opportunity: MainScaffold removes the
        // nav graph in RESTORE_PREPARING and owns the mandatory close/reopen UI after Room closes.
        onRestoreInitiated = onComplete,
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.onboarding_recovery_backup_title),
                onBack = goBack,
            )
            Text(
                text = stringResource(R.string.onboarding_recovery_backup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.md,
                ),
            )
            ManagementRow(
                title = stringResource(R.string.settings_backup_folder),
                subtitle = backupFolderUri?.let { FolderPathFormatter.format(context, it) }
                    ?: stringResource(R.string.settings_no_folder_selected),
                enabled = !busy,
                onClick = { backupFolderPicker.launch(null) },
            )
            backupFolderError?.let { error ->
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
        },
    )
}
