package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import com.v1per.synthx.R
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.components.rememberNotificationPermissionGate
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
    val notificationGate = rememberNotificationPermissionGate()

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
                // other fresh install.
                onFreshInstall = viewModel::goToFolderSetup,
                onRecoverFromBackup = viewModel::goToRecoveryFolderSetup,
            )

            OnboardingStep.FolderSetup -> FolderSetupStep(
                libraryManagementViewModel = libraryManagementViewModel,
                backupsAndRestoreViewModel = backupsAndRestoreViewModel,
                onBack = viewModel::goToChoice,
                // Advance only if the scan really started — a null return leaves the wizard here
                // with the error already shown under the folder row.
                // The first scan is the earliest thing on a fresh install that posts a
                // notification, so this is where the permission is first explained and asked for —
                // never over the Welcome screen. The scan starts as soon as the user continues,
                // without waiting on the system prompt's answer.
                onDone = {
                    notificationGate.runAfterRationale {
                        if (libraryManagementViewModel.startFirstScan() != null) viewModel.goToScanning()
                    }
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
 * No [androidx.activity.compose.BackHandler] here, unlike the recovery steps below — deliberate:
 * wizard position is persisted, so a back-press here that finishes the
 * Activity is harmless. No progress has been made yet at Choice, so relaunching lands right back
 * on this same screen instead of losing anything or resuming somewhere confusing.
 */
@Composable
private fun ChoiceStep(onFreshInstall: () -> Unit, onRecoverFromBackup: () -> Unit) {
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
