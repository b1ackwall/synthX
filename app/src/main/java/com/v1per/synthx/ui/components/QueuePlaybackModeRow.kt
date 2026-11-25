package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.theme.Spacing

/**
 * Shuffle / Repeat row: two [PlayerToggleButton]s. Each is a filled square in the player's content color while
 * on (shuffle on; repeat All or One) and the plain icon at full strength while off. Repeat One draws the
 * repeat-once glyph. The accent color is deliberately not used here.
 *
 * Accessibility services get the state the fill shows: Shuffle is a switch that is on or off, and Repeat states
 * Off, All or One after its name. Taps and the accessibility click keep the toggles' drag-resistant handling.
 * [enabled] is the player's fade gate: it only stops clicks while the row is fading, and never dims the toggles.
 *
 * No "Autoplay" button, not even a disabled one: Apple Music's Autoplay pulls replacement
 * tracks from a streaming catalog once a queue ends, which has no equivalent against a
 * local-only library, and a dead button next to two working ones reads as broken, not
 * "coming soon". Worth adding only with an actual local analog (e.g. "keep playing similar
 * songs from your library").
 */
@Composable
fun QueuePlaybackModeRow(
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PlayerToggleButton(
            checked = shuffleEnabled,
            onClick = onShuffleClick,
            icon = Icons.Filled.Shuffle,
            contentDescription = stringResource(R.string.cd_shuffle),
            interactive = enabled,
        )
        PlayerToggleButton(
            checked = repeatMode != RepeatMode.Off,
            onClick = onRepeatClick,
            icon = if (repeatMode == RepeatMode.One) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            contentDescription = stringResource(R.string.cd_repeat),
            stateDescription = stringResource(
                when (repeatMode) {
                    RepeatMode.Off -> R.string.cd_repeat_off
                    RepeatMode.All -> R.string.cd_repeat_all
                    RepeatMode.One -> R.string.cd_repeat_one
                },
            ),
            interactive = enabled,
        )
    }
}

/** Off -> All -> One -> Off. */
fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.Off -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}
