package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import com.v1per.synthx.R
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.components.rememberNotificationPermissionGate
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * First-run wizard shown by [com.v1per.synthx.ui.onboardingRoutes] when
 * [com.v1per.synthx.shouldShowOnboarding] is true: onboarding not complete, or a first-scan
 * request still pending. Internal steps (see [OnboardingStep]) share one nav route, including
 * the recovery branch.
 *
 * Deliberately reuses [LibraryManagementViewModel] and [BackupsAndRestoreViewModel] wholesale
 * (constructed here, scoped to this route like any other screen's ViewModel) rather than
 * reimplementing folder validation/persistence/scan-triggering. Picking the music or New Arrivals
 * folder only persists it ([LibraryManagementViewModel.saveOnboardingMusicFolder],
 * [LibraryManagementViewModel.saveOnboardingNewArrivalsFolder]); tapping Done fires the one
 * [LibraryManagementViewModel.startFirstScan] call (completion flag + REPLACE-enqueued
 * [com.v1per.synthx.data.scanner.LibraryScanWorker] + tracked request id), which ScanningStep then
 * observes by that id, and accepting that scan ([LibraryManagementViewModel.completeFirstScan])
 * queues the New Arrivals folder's first scan. Recovery calls its own persistence methods on the
 * same ViewModel, so changing branches also retains the current folder state.
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
            // Shown for 2.5 s only, so a screen reader announces it as it appears.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
