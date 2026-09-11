package com.v1per.synthx.ui.screens.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.FolderPathFormatter
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.screens.librarymanagement.ManagementRow
import com.v1per.synthx.ui.screens.librarymanagement.MusicFolderSection
import com.v1per.synthx.ui.screens.librarymanagement.NewArrivalsFolderSection
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * First-run wizard shown by [com.v1per.synthx.ui.onboardingRoutes] whenever
 * [com.v1per.synthx.preferences.PreferencesManager.musicFolderUri] is null. Internal steps
 * (see [OnboardingStep]) share one nav route, including the recovery branch.
 *
 * Deliberately reuses [LibraryManagementViewModel] and [BackupsAndRestoreViewModel] wholesale
 * (constructed here, scoped to this route like any other screen's ViewModel) rather than
 * reimplementing folder validation/persistence/scan-triggering — saveMusicFolder's existing
 * safety-backup + [com.v1per.synthx.data.scanner.LibraryScanWorker] REPLACE-enqueue side effect
 * is exactly the first-scan trigger fresh install needs. Recovery calls separate persistence
 * methods on the same ViewModel, so changing branches also retains the current folder state.
 */
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val context = LocalContext.current
    val step by viewModel.step.collectAsState()

    val libraryManagementViewModel: LibraryManagementViewModel = viewModel(
        factory = LibraryManagementViewModel.Factory(context),
    )
    val backupsAndRestoreViewModel: BackupsAndRestoreViewModel = viewModel(
        factory = BackupsAndRestoreViewModel.Factory(context),
    )

    when (step) {
        OnboardingStep.Choice -> ChoiceStep(
            onFreshInstall = {
                // Recovery may have persisted a root without ever scanning it. Switching to
                // fresh install must start its usual scan before Done can enter ScanningStep.
                if (viewModel.returningFromRecovery) {
                    libraryManagementViewModel.musicFolderUri.value?.let {
                        libraryManagementViewModel.saveMusicFolder(it)
                    }
                }
                viewModel.goToFolderSetup()
            },
            onRecoverFromBackup = viewModel::goToRecoveryFolderSetup,
        )
        OnboardingStep.FolderSetup -> FolderSetupStep(
            libraryManagementViewModel = libraryManagementViewModel,
            backupsAndRestoreViewModel = backupsAndRestoreViewModel,
            onBack = viewModel::goToChoice,
            onDone = viewModel::goToScanning,
        )
        OnboardingStep.Scanning -> ScanningStep(
            libraryManagementViewModel = libraryManagementViewModel,
            onComplete = onOnboardingComplete,
        )
        OnboardingStep.RecoveryFolderSetup -> RecoveryFolderSetupStep(
            libraryManagementViewModel = libraryManagementViewModel,
            onBack = viewModel::goToChoice,
            onDone = viewModel::goToRecoveryBackupSelect,
        )
        OnboardingStep.RecoveryBackupSelect -> RecoveryBackupSelectStep(
            backupsAndRestoreViewModel = backupsAndRestoreViewModel,
            onBack = viewModel::goToRecoveryFolderSetup,
            onComplete = onOnboardingComplete,
        )
    }
}

@Composable
private fun ChoiceStep(
    onFreshInstall: () -> Unit,
    onRecoverFromBackup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(Spacing.HorizontalPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.xxl))

        Button(
            onClick = onFreshInstall,
            modifier = Modifier.fillMaxWidth().height(Spacing.xxl),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceVariant,
                contentColor = TextPrimary,
            ),
        ) {
            Text(
                text = stringResource(R.string.onboarding_fresh_install),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        OutlinedButton(
            onClick = onRecoverFromBackup,
            modifier = Modifier.fillMaxWidth().height(Spacing.xxl),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
        ) {
            Text(
                text = stringResource(R.string.onboarding_recover_backup),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun FolderSetupStep(
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
    val backupFolderUri by backupsAndRestoreViewModel.backupFolderUri.collectAsState()
    val backupFolderError by backupsAndRestoreViewModel.backupFolderError.collectAsState()

    // Resolved here, not inside the picker callbacks below — those aren't composable scopes.
    val permissionDeniedMessage = stringResource(R.string.error_folder_permission_denied)

    // Same OpenDocumentTree + takePersistableUriPermission wiring as
    // LibraryManagementScreen/BackupsAndRestoreScreen — this glue is Activity-result plumbing,
    // not the business logic those two screens' ViewModels already own, so it's replicated
    // rather than pulled out from behind those screens' own private launchers.
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
                libraryManagementViewModel.saveMusicFolder(newUri)
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
                libraryManagementViewModel.saveNewArrivalsFolder(newUri)
            } catch (e: SecurityException) {
                libraryManagementViewModel.setNewArrivalsFolderError(permissionDeniedMessage)
            }
        }
    }

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

@Composable
private fun ScanningStep(
    libraryManagementViewModel: LibraryManagementViewModel,
    onComplete: () -> Unit,
) {
    val isScanning by libraryManagementViewModel.isScanning.collectAsState()
    val rescanStatus by libraryManagementViewModel.rescanStatus.collectAsState()

    // rescanStatus is only null before the just-enqueued work's WorkInfo has been observed at
    // all (or if a prior run was already acknowledged — moot here, this ViewModel is freshly
    // constructed for the wizard). Waiting on "not scanning AND a status exists" — rather than
    // catching the false->true->false transition — also correctly handles a scan that finishes
    // before this step even starts collecting (a near-empty folder can do this in well under a
    // frame), which a transition-based check would miss entirely.
    LaunchedEffect(isScanning, rescanStatus) {
        if (!isScanning && rescanStatus != null) {
            onComplete()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = TextSecondary, strokeWidth = 2.dp)
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.onboarding_scanning_title),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            rescanStatus?.let { status ->
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
                )
            }
        }
    }
}
