package com.v1per.synthx.ui.screens.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.v1per.synthx.R
import com.v1per.synthx.ui.screens.librarymanagement.FirstScanState
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Observes the first scan by its own request id (see [LibraryManagementViewModel.firstScanState])
 * and branches on the real terminal state: SUCCEEDED hands over to Home, FAILED/CANCELLED stop
 * here with Retry / Change folder instead of dropping the user into an empty Home.
 *
 * Back is swallowed while the scan runs — there is nowhere honest to go, and the scan would keep
 * running behind whatever screen we popped to — and routes to Change folder once it has failed.
 */
@Composable
internal fun ScanningStep(
    libraryManagementViewModel: LibraryManagementViewModel,
    onChangeFolder: () -> Unit,
    onComplete: () -> Unit,
) {
    val state by libraryManagementViewModel.firstScanState.collectAsState()
    // Read through State: the one-shot effect below must call the latest onComplete, not the one it launched with.
    val currentOnComplete by rememberUpdatedState(onComplete)

    // Two fixed call sites, toggled by `enabled`, rather than one per branch: exactly one is
    // active at any time, and neither is disposed/re-registered as the state moves along.
    BackHandler(enabled = state !is FirstScanState.Failed) { /* swallowed while scanning */ }
    BackHandler(enabled = state is FirstScanState.Failed, onBack = onChangeFolder)

    when (val current = state) {
        is FirstScanState.Running -> ScanningProgress(status = current.status)

        FirstScanState.Succeeded -> {
            LaunchedEffect(Unit) { currentOnComplete() }
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
            CircularProgressIndicator(color = TextSecondary, strokeWidth = Strokes.ProgressRing)
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
                if (cancelled) {
                    R.string.onboarding_scan_cancelled_title
                } else {
                    R.string.onboarding_scan_failed_title
                },
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
