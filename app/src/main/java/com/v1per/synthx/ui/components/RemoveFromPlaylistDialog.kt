package com.v1per.synthx.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
// Aliased: the Material3 `Surface` composable owns the simple name in Compose files.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Asked before a song is removed from a playlist. Deliberately neutral — TextPrimary confirm, no
 * red, no typed input — because the song itself is untouched and can be re-added, unlike
 * [DeleteSongDialog]/[DeletePlaylistDialog]. Same shape as [PlayNextDuplicateDialog].
 */
@Composable
fun RemoveFromPlaylistDialog(songTitle: String, playlistName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_from_playlist_dialog_title)) },
        text = {
            Text(stringResource(R.string.remove_from_playlist_dialog_message, songTitle, playlistName))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) {
                Text(stringResource(R.string.remove_from_playlist_dialog_confirm))
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
