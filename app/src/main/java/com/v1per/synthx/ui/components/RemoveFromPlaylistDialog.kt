package com.v1per.synthx.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R

/**
 * Asked before a song is removed from a playlist. Deliberately not destructive — a Primary
 * confirm, no red, no typed input — because the song itself is untouched and can be re-added,
 * unlike [DeleteSongDialog]/[DeletePlaylistDialog]. Same shape as [PlayNextDuplicateDialog].
 */
@Composable
fun RemoveFromPlaylistDialog(songTitle: String, playlistName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_from_playlist_dialog_title)) },
        text = {
            Text(stringResource(R.string.remove_from_playlist_dialog_message, songTitle, playlistName))
        },
        confirmButton = {
            SynthXPrimaryButton(
                onClick = onConfirm,
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.remove_from_playlist_dialog_confirm))
            }
        },
        dismissButton = {
            SynthXSecondaryButton(
                onClick = onDismiss,
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
