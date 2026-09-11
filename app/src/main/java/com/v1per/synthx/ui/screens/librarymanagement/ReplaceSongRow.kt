package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R

/**
 * Entry point for the Replace Song wizard.
 *
 * Gated behind the same [com.v1per.synthx.preferences.DeleteSongsGate] toggle the delete action
 * uses, and for the same reason: the replacement ends by permanently deleting a song and its
 * audio file, so it is exactly as destructive. The subtitle says why the row is inert rather than
 * leaving a dead row with no explanation — [ManagementRow] has no dimmed disabled treatment of
 * its own.
 */
@Composable
internal fun ReplaceSongRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ManagementRow(
        title = stringResource(R.string.settings_replace_song),
        subtitle = stringResource(
            if (enabled) {
                R.string.settings_replace_song_subtitle
            } else {
                R.string.settings_replace_song_disabled
            }
        ),
        subtitleMaxLines = 2,
        enabled = enabled,
        onClick = onClick,
    )
}
