package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.data.repository.DeleteSongPreview
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
// Aliased: the Material3 `Surface` composable owns the simple name in Compose files.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Confirmation for the one irreversible action in the app. Shared by the Issues screen
 * and the song actions sheet so both describe the same consequences in the same words.
 *
 * Reads [DestructiveActionTracker] directly, the same way MainScaffold reads
 * BackupFolderLaunchCheck.grantLost — a process-wide StateFlow, not plumbed through every
 * caller's own ViewModel just to reach this dialog.
 *
 * This removes the audio file from the device as well as the library row,
 * so the confirm button is gated behind typing the word "Delete" — see [TypedConfirmationField].
 * The typed text is local to the dialog and reset by its being torn down on dismissal (both call
 * sites drop it from composition rather than hiding it), so a cancelled attempt never leaves a
 * pre-satisfied gate behind for the next one.
 */
@Composable
fun DeleteSongDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    previewState: DeleteSongPreviewState = DeleteSongPreviewState.Ready(DeleteSongPreview(emptyList())),
    onRetryPreview: () -> Unit = {},
    // Cosmetic reinforcement of the caller's own in-flight guard (e.g.
    // SongActionsViewModel.deleteInProgress) — a double-tap is already prevented at the
    // ViewModel level regardless of whether this has a chance to take visible effect first.
    // Defaults to true so a future caller that doesn't have its own in-flight state isn't
    // forced to wire this up.
    confirmEnabled: Boolean = true,
) {
    val destructiveActionCount by DestructiveActionTracker.count.collectAsState()
    // The button's own label is the word to type, read from the one resource that provides both.
    val requiredWord = stringResource(R.string.action_delete)
    var typedConfirmation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_song_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DeleteSongDisclosure(previewState, destructiveActionCount, onRetryPreview)
                Spacer(modifier = Modifier.height(Spacing.md))
                TypedConfirmationField(
                    requiredWord = requiredWord,
                    value = typedConfirmation,
                    onValueChange = { typedConfirmation = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = previewState is DeleteSongPreviewState.Ready &&
                    typedConfirmationMatches(typedConfirmation, requiredWord) && confirmEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = Error),
            ) {
                Text(text = requiredWord)
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

/** Extracted dialog body so its consequence disclosure can be tested without a platform window. */
@Composable
internal fun DeleteSongDisclosure(
    previewState: DeleteSongPreviewState,
    destructiveActionCount: Int,
    onRetryPreview: () -> Unit,
) {
    Text(stringResource(R.string.delete_song_dialog_message))
    Spacer(modifier = Modifier.height(Spacing.md))
    when (val state = previewState) {
        DeleteSongPreviewState.Loading -> Text(stringResource(R.string.delete_song_preview_loading))
        DeleteSongPreviewState.Failed -> {
            Text(stringResource(R.string.delete_song_preview_failed), color = Error)
            TextButton(onClick = onRetryPreview) { Text(stringResource(R.string.action_retry)) }
        }
        is DeleteSongPreviewState.Ready -> {
            Text(stringResource(R.string.delete_song_playlists_heading, state.preview.playlists.size))
            if (state.preview.playlists.isEmpty()) {
                Text(stringResource(R.string.delete_song_no_playlists))
            } else {
                state.preview.playlists.forEach { impact ->
                    Text(
                        if (impact.willBecomeEmpty) {
                            stringResource(R.string.delete_song_playlist_will_empty, impact.name)
                        } else impact.name
                    )
                }
            }
        }
    }
    if (destructiveActionCount > 0) {
        Text(
            stringResource(R.string.destructive_action_warning, destructiveActionCount),
            color = Error,
        )
    }
}

sealed interface DeleteSongPreviewState {
    data object Loading : DeleteSongPreviewState
    data object Failed : DeleteSongPreviewState
    data class Ready(val preview: DeleteSongPreview) : DeleteSongPreviewState
}
