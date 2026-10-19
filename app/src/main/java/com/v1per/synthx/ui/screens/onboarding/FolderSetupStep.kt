package com.v1per.synthx.ui.screens.onboarding

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.FolderPathFormatter
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.screens.librarymanagement.ManagementRow
import com.v1per.synthx.ui.screens.librarymanagement.MusicFolderSection
import com.v1per.synthx.ui.screens.librarymanagement.MusicFolderRequiredHint
import com.v1per.synthx.ui.screens.librarymanagement.NewArrivalsFolderSection
import com.v1per.synthx.ui.screens.librarymanagement.handleFolderPick
import com.v1per.synthx.ui.screens.librarymanagement.musicFolderStepGate
import com.v1per.synthx.ui.screens.librarymanagement.takeFolderGrant
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
internal fun FolderSetupStep(
    libraryManagementViewModel: LibraryManagementViewModel,
    backupsAndRestoreViewModel: BackupsAndRestoreViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current

    val musicFolderUri by libraryManagementViewModel.musicFolderUri.collectAsState()
    val folderError by libraryManagementViewModel.folderError.collectAsState()
    val newArrivalsFolderUri by libraryManagementViewModel.newArrivalsFolderUri.collectAsState()
    val newArrivalsFolderError by libraryManagementViewModel.newArrivalsFolderError.collectAsState()
    val newArrivalsFolderNotice by libraryManagementViewModel.newArrivalsFolderNotice.collectAsState()
    val backupFolderUri by backupsAndRestoreViewModel.backupFolderUri.collectAsState()
    val backupFolderError by backupsAndRestoreViewModel.backupFolderError.collectAsState()

    // Resolved here, not inside the picker callbacks below — those aren't composable scopes.
    val grantRefusedMessage = stringResource(R.string.error_folder_grant_refused)

    // Same OpenDocumentTree + takePersistableUriPermission wiring as
    // LibraryManagementScreen/BackupsAndRestoreScreen — this glue is Activity-result plumbing,
    // not the business logic those two screens' ViewModels already own, so it's replicated
    // rather than pulled out from behind those screens' own private launchers.
    val musicFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // Read + write — graduation moves files into this tree. See
        // LibraryManagementScreen's music folder picker.
        handleFolderPick(
            picked = uri,
            takePermission = context::takeFolderGrant,
            onPicked = { libraryManagementViewModel.saveOnboardingMusicFolder(it) },
            onGrantRefused = { libraryManagementViewModel.setFolderError(grantRefusedMessage) },
        )
    }

    val newArrivalsFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // Read + write — graduation deletes the original from this tree. See
        // LibraryManagementScreen's New Arrivals picker.
        handleFolderPick(
            picked = uri,
            takePermission = context::takeFolderGrant,
            onPicked = { libraryManagementViewModel.saveNewArrivalsFolder(it) },
            onGrantRefused = { libraryManagementViewModel.setNewArrivalsFolderError(grantRefusedMessage) },
        )
    }

    val backupFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        handleFolderPick(
            picked = uri,
            takePermission = context::takeFolderGrant,
            onPicked = { backupsAndRestoreViewModel.saveBackupFolder(it) },
            onGrantRefused = { backupsAndRestoreViewModel.setBackupFolderError(grantRefusedMessage) },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        DetailTopBar(
            title = stringResource(R.string.onboarding_folder_setup_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.onboarding_folder_setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.md,
                ),
            )

            // Mandatory — Done stays disabled below until this is set.
            MusicFolderSection(
                folderPath = musicFolderUri?.let { FolderPathFormatter.format(context, it) },
                folderError = folderError,
                onClick = { musicFolderPicker.launch(null) },
            )
            // Optional, skippable.
            NewArrivalsFolderSection(
                folderPath = newArrivalsFolderUri?.let { FolderPathFormatter.format(context, it) },
                folderError = newArrivalsFolderError,
                folderNotice = newArrivalsFolderNotice,
                onClick = { newArrivalsFolderPicker.launch(null) },
            )
            // Optional, skippable. Reuses the same row shape BackupSection uses for this row.
            ManagementRow(
                title = stringResource(R.string.settings_backup_folder),
                subtitle = backupFolderUri?.let { FolderPathFormatter.format(context, it) }
                    ?: stringResource(R.string.settings_no_folder_selected),
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
        }

        // Done is disabled until a music folder is set; say so instead of leaving a dead button.
        // A cancelled picker stays silent on every row — this is the one place a cancel leaves the
        // user stuck, and the hint is what explains why.
        val folderStepGate = musicFolderStepGate(musicFolderSelected = musicFolderUri != null)
        if (folderStepGate.showRequiredHint) {
            MusicFolderRequiredHint()
        }

        Button(
            onClick = onDone,
            enabled = folderStepGate.doneEnabled,
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
