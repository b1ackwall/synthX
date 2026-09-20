package com.v1per.synthx.ui.screens.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.FirstScanState
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
import kotlinx.coroutines.delay

/**
 * First-run wizard shown by [com.v1per.synthx.ui.onboardingRoutes] whenever
 * [com.v1per.synthx.preferences.PreferencesManager.musicFolderUri] is null. Internal steps
 * (see [OnboardingStep]) share one nav route, including the recovery branch.
 *
 * Deliberately reuses [LibraryManagementViewModel] and [BackupsAndRestoreViewModel] wholesale
 * (constructed here, scoped to this route like any other screen's ViewModel) rather than
 * reimplementing folder validation/persistence/scan-triggering. Picking a folder only persists it
 * ([LibraryManagementViewModel.saveOnboardingMusicFolder]); tapping Done fires the one
 * [LibraryManagementViewModel.startFirstScan] call (completion flag + REPLACE-enqueued
 * [com.v1per.synthx.data.scanner.LibraryScanWorker] + tracked request id), which ScanningStep then
 * observes by that id. Recovery calls its own persistence methods on the same ViewModel, so
 * changing branches also retains the current folder state.
 */
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    onRestoreTerminalShown: (UUID) -> Unit = {},
    viewModel: OnboardingViewModel = viewModel(
        factory = OnboardingViewModel.Factory(LocalContext.current),
    ),
) {
    val context = LocalContext.current
    val step by viewModel.step.collectAsState()

    val libraryManagementViewModel: LibraryManagementViewModel = viewModel(
        factory = LibraryManagementViewModel.Factory(context),
    )
    val backupsAndRestoreViewModel: BackupsAndRestoreViewModel = viewModel(
        factory = BackupsAndRestoreViewModel.Factory(context),
    )

    // Only true when this instance actually resumed a persisted mid-wizard position (process
    // death/swipe-away) — see OnboardingViewModel.resumedFromPersistedStep. Auto-dismisses;
    // this is an orientation notice, not something the user needs to act on.
    var showResumeNotice by remember { mutableStateOf(viewModel.resumedFromPersistedStep) }
    LaunchedEffect(showResumeNotice) {
        if (showResumeNotice) {
            delay(2500)
            showResumeNotice = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (step) {
            OnboardingStep.Choice -> ChoiceStep(
                // Nothing to start here even after an abandoned recovery: the folder it already
                // persisted shows up in FolderSetup, and Done there starts the first scan like any
                // other fresh install (P9-T1 folded finishFreshInstallFromAbandonedRecovery into it).
                onFreshInstall = viewModel::goToFolderSetup,
                onRecoverFromBackup = viewModel::goToRecoveryFolderSetup,
            )
            OnboardingStep.FolderSetup -> FolderSetupStep(
                libraryManagementViewModel = libraryManagementViewModel,
                backupsAndRestoreViewModel = backupsAndRestoreViewModel,
                onBack = viewModel::goToChoice,
                // Advance only if the scan really started — a null return leaves the wizard here
                // with the error already shown under the folder row.
                onDone = {
                    if (libraryManagementViewModel.startFirstScan() != null) viewModel.goToScanning()
                },
            )
            OnboardingStep.Scanning -> ScanningStep(
                libraryManagementViewModel = libraryManagementViewModel,
                onChangeFolder = {
                    libraryManagementViewModel.abandonFirstScan()
                    viewModel.goToFolderSetup()
                },
                onComplete = {
                    libraryManagementViewModel.completeFirstScan()
                    onOnboardingComplete()
                },
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
                onRestoreTerminalShown = onRestoreTerminalShown,
            )
        }

        if (showResumeNotice) {
            ResumingRecoveryNotice()
        }
    }
}

@Composable
private fun ResumingRecoveryNotice() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.onboarding_resuming_recovery),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

/**
 * No [androidx.activity.compose.BackHandler] here, unlike the recovery steps below — deliberate
 * (P7-T2): now that wizard position is persisted (R7-F13), a back-press here that finishes the
 * Activity is harmless. No progress has been made yet at Choice, so relaunching lands right back
 * on this same screen instead of losing anything or resuming somewhere confusing.
 */
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
        // user stuck, and the hint is what explains why (P9-T4).
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

/**
 * Observes the first scan by its own request id (see [LibraryManagementViewModel.firstScanState])
 * and branches on the real terminal state: SUCCEEDED hands over to Home, FAILED/CANCELLED stop
 * here with Retry / Change folder instead of dropping the user into an empty Home (P9-T1 / F10).
 *
 * Back is swallowed while the scan runs — there is nowhere honest to go, and the scan would keep
 * running behind whatever screen we popped to — and routes to Change folder once it has failed.
 */
@Composable
private fun ScanningStep(
    libraryManagementViewModel: LibraryManagementViewModel,
    onChangeFolder: () -> Unit,
    onComplete: () -> Unit,
) {
    val state by libraryManagementViewModel.firstScanState.collectAsState()

    // Two fixed call sites, toggled by `enabled`, rather than one per branch: exactly one is
    // active at any time, and neither is disposed/re-registered as the state moves along.
    BackHandler(enabled = state !is FirstScanState.Failed) { /* swallowed while scanning */ }
    BackHandler(enabled = state is FirstScanState.Failed, onBack = onChangeFolder)

    when (val current = state) {
        is FirstScanState.Running -> ScanningProgress(status = current.status)
        FirstScanState.Succeeded -> {
            LaunchedEffect(Unit) { onComplete() }
            ScanningProgress(status = null)
        }
        is FirstScanState.Failed -> {
            // A revoked grant clears the stored folder — retrying the same (now absent) folder
            // cannot work, so only Change folder is offered. Read from the ViewModel's mirrored
            // state, the same one FolderSetup's Done gate uses.
            val folder by libraryManagementViewModel.musicFolderUri.collectAsState()
            ScanFailed(
                cancelled = current.cancelled,
                message = current.message,
                canRetry = folder != null,
                onRetry = { libraryManagementViewModel.retryFirstScan() },
                onChangeFolder = onChangeFolder,
            )
        }
    }
}

@Composable
private fun ScanningProgress(status: String?) {
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
            status?.let {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
                )
            }
        }
    }
}

@Composable
private fun ScanFailed(
    cancelled: Boolean,
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(Spacing.HorizontalPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                if (cancelled) R.string.onboarding_scan_cancelled_title
                else R.string.onboarding_scan_failed_title,
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Error,
        )
        Spacer(modifier = Modifier.height(Spacing.xxl))

        if (canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(Spacing.xxl),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.action_retry),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.md))
        }
        OutlinedButton(
            onClick = onChangeFolder,
            modifier = Modifier.fillMaxWidth().height(Spacing.xxl),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
        ) {
            Text(
                text = stringResource(R.string.onboarding_scan_change_folder),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
