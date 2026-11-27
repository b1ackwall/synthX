package com.v1per.synthx.ui.screens.replacesong

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.components.SynthXAlertDialog
import com.v1per.synthx.ui.components.SynthXButtonWidth
import com.v1per.synthx.ui.components.SynthXPrimaryButton
import com.v1per.synthx.ui.components.SynthXSecondaryButton
import com.v1per.synthx.ui.components.SynthXSnackbarHost
import com.v1per.synthx.ui.components.showSynthXSnackbar

/**
 * Swaps one song's library identity onto another and completely deletes the original.
 *
 * A three-step wizard sharing one nav route, following
 * [com.v1per.synthx.ui.screens.onboarding.OnboardingScreen]'s shape: a `when(step)` over a thin
 * sequencer ViewModel, per-step `onBack`/selection callbacks, and all the real work delegated —
 * here entirely to [com.v1per.synthx.data.repository.SongRepository.replaceSong].
 *
 * Renders its own [DetailTopBar] per step rather than taking one from the route (the convention
 * the other settings sub-screens follow), because back has to mean "previous step" for two of the
 * three steps — the same reason `RecoverySteps` builds its own. [SettingsDetailScaffold] is
 * likewise not used: two of the three steps are a `LazyColumn` over the whole library, which
 * cannot live inside that scaffold's `verticalScroll` column.
 */
@Composable
fun ReplaceSongScreen(
    onExit: () -> Unit,
    viewModel: ReplaceSongViewModel = viewModel(
        factory = ReplaceSongViewModel.Factory(LocalContext.current),
    ),
) {
    val resources = LocalResources.current
    val step by viewModel.step.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val conflictResolution by viewModel.conflictResolution.collectAsState()
    val outcome by viewModel.outcome.collectAsState()
    val cleanupRetryInProgress by viewModel.cleanupRetryInProgress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val replaceInFlight = outcome is ReplaceSongOutcome.InProgress

    val goBack: () -> Unit = {
        // Blocked outright while the replace is committing: it deletes a file and mutates live
        // playback, and there is nothing sensible to return to mid-way. Same guard
        // RecoveryBackupSelectStep applies to its own destructive submission.
        if (!replaceInFlight) {
            when (step) {
                ReplaceSongStep.PickOld -> onExit()
                ReplaceSongStep.PickNew -> viewModel.backToPickOld()
                ReplaceSongStep.ReviewAndConfirm -> viewModel.backToPickNew()
            }
        }
    }
    BackHandler(onBack = goBack)

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        DetailTopBar(
            title = stringResource(
                when (step) {
                    ReplaceSongStep.PickOld -> R.string.replace_song_step_pick_old
                    ReplaceSongStep.PickNew -> R.string.replace_song_step_pick_new
                    ReplaceSongStep.ReviewAndConfirm -> R.string.replace_song_step_review
                },
            ),
            onBack = goBack,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when (step) {
                ReplaceSongStep.PickOld -> ReplaceSongPickerStep(
                    subtitleRes = R.string.replace_song_pick_old_subtitle,
                    query = query,
                    songs = results,
                    onQueryChange = viewModel::onQueryChange,
                    onSelect = viewModel::selectOldSong,
                )

                ReplaceSongStep.PickNew -> ReplaceSongPickerStep(
                    subtitleRes = R.string.replace_song_pick_new_subtitle,
                    query = query,
                    songs = results,
                    onQueryChange = viewModel::onQueryChange,
                    onSelect = viewModel::selectNewSong,
                )

                ReplaceSongStep.ReviewAndConfirm -> when (val current = preview) {
                    // Idle is unreachable on this step (selectNewSong starts the preview as it
                    // advances), but is rendered as the same cover rather than as nothing, so a
                    // future path into the step can never show an empty screen.
                    ReplaceSongPreviewState.Idle,
                    is ReplaceSongPreviewState.Loading,
                    ->
                        ReplaceSongBusyCover(R.string.replace_song_preview_loading)

                    is ReplaceSongPreviewState.Failed -> ReplaceSongPreviewFailure(
                        message = current.message,
                        onRetry = if (current.retryable) viewModel::retryPreview else null,
                    )

                    is ReplaceSongPreviewState.Ready -> ReplaceSongReviewStep(
                        preview = current.preview,
                        conflictResolution = conflictResolution,
                        confirmEnabled = !replaceInFlight,
                        onConflictResolutionChange = viewModel::setConflictResolution,
                        // `current` carries the membership snapshot validated atomically at commit.
                        onConfirm = { viewModel.replace(current) },
                    )
                }
            }

            if (replaceInFlight) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background.copy(alpha = 0.85f)),
                ) {
                    ReplaceSongBusyCover(R.string.replace_song_working)
                }
            }
            SynthXSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    val complete = outcome as? ReplaceSongOutcome.Complete
    ReplaceSongSuccessAcknowledgement(
        complete = complete,
        snackbarHostState = snackbarHostState,
        message = resources.getString(R.string.replace_song_done),
        onExit = onExit,
    )

    val dialogMessage = when (val current = outcome) {
        is ReplaceSongOutcome.Failed -> current.message
        is ReplaceSongOutcome.Complete -> current.warning
        else -> null
    }
    dialogMessage?.let { message ->
        // A failure leaves the user on the review step to retry or back out; an acknowledged
        // warning has nothing left to do here, so it closes the wizard.
        val onAcknowledge: () -> Unit = {
            if (!cleanupRetryInProgress) {
                viewModel.dismissOutcome()
                if (complete != null) onExit()
            }
        }
        ReplaceSongOutcomeDialog(
            message = message,
            onAcknowledge = onAcknowledge,
            onRetry = complete?.retryFilePath?.let { filePath -> { viewModel.retryAudioFileDeletion(filePath) } },
            retryEnabled = !cleanupRetryInProgress,
        )
    }
}

/**
 * The wizard's failure or completion-with-warning message. OK, Back and an outside tap call [onAcknowledge];
 * [onRetry], when the old audio file is still on the device, adds Retry, which [retryEnabled] turns off while a
 * retry runs.
 */
@Composable
internal fun ReplaceSongOutcomeDialog(
    message: String,
    onAcknowledge: () -> Unit,
    onRetry: (() -> Unit)?,
    retryEnabled: Boolean,
) {
    SynthXAlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(stringResource(R.string.screen_replace_song)) },
        text = { Text(message) },
        confirmButton = {
            SynthXPrimaryButton(onClick = onAcknowledge, width = SynthXButtonWidth.Dialog) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = onRetry?.let { retry ->
            {
                SynthXSecondaryButton(onClick = retry, width = SynthXButtonWidth.Dialog, enabled = retryEnabled) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        },
    )
}

/** Warning-free completion is acknowledged transiently; warnings remain blocking above. */
@Composable
internal fun ReplaceSongSuccessAcknowledgement(
    complete: ReplaceSongOutcome.Complete?,
    snackbarHostState: SnackbarHostState,
    message: String,
    onExit: () -> Unit,
) {
    LaunchedEffect(complete) {
        if (complete != null && complete.warning == null) {
            snackbarHostState.showSynthXSnackbar(message, duration = SnackbarDuration.Short)
            onExit()
        }
    }
}
