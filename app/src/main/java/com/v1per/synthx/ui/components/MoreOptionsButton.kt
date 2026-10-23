package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.player.LocalPlayerContentColors
import com.v1per.synthx.ui.player.PlayerContentColors
import com.v1per.synthx.ui.theme.IconSizes

/**
 * Same [LocalSongActions] trigger in both header modes, just relocated per layout.
 *
 * Internal (not private) because both PlayerSkeletonExpandedContent and
 * PlayerSkeletonQueueContent use it — co-located here as a shared player-chrome component.
 */
@Composable
internal fun MoreOptionsButton(song: Song, enabled: Boolean) {
    val songActions = LocalSongActions.current
    val contentColors = LocalPlayerContentColors.current ?: PlayerContentColors.Light

    // Stable-identity click handler, same pattern as QueueModeState.onQueueClick:
    // DragResistantIconButton's dragResistantClickable keys its
    // pointerInput on (interactionSource, enabled, onClick), so a fresh inline lambda here
    // would restart the gesture coroutine every recomposition — and both call sites of this
    // composable (PlayerSkeletonExpandedContent, PlayerSkeletonQueueContent) recompose every
    // animation frame while their respective drag/crossfade is in flight. Built once via a no-key remember, reading
    // song/songActions through rememberUpdatedState so it still acts on whichever song/
    // songActions are current at invocation time despite being constructed only once.
    val currentSong by rememberUpdatedState(song)
    val currentSongActions by rememberUpdatedState(songActions)
    val onClick = remember {
        { currentSongActions.show(currentSong, null, SongActionSource.Player) }
    }

    DragResistantIconButton(
        onClick = onClick,
        enabled = enabled,
    ) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.cd_more_options),
            tint = contentColors.content,
            modifier = Modifier.size(IconSizes.Standard),
        )
    }
}
