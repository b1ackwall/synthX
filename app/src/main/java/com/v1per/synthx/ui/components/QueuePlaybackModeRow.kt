package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Shuffle / Repeat row. Both tint [TextPrimary] when active (vs. [TextSecondary] when
 * inactive) — the same "tint marks the active state" convention as the play/pause icon
 * elsewhere in the player, rather than swapping to an "_on" icon glyph that
 * material-icons-extended doesn't ship. Accent was pulled back out of scope here (Phase E);
 * TextPrimary was chosen over TextSecondary for the active state specifically so the on/off
 * states stay visually distinct instead of collapsing to the same gray.
 *
 * There used to be a third "Autoplay" button here. It was removed rather than wired up or
 * disabled-with-a-tooltip: Apple Music's Autoplay pulls replacement tracks from a streaming
 * catalog once a queue ends, which has no equivalent against a local-only library, so there
 * was nothing real to build behind it. A dead button sitting next to two working ones read as
 * broken, not "coming soon" — removing it was the honest option until there's an actual local
 * analog (e.g. "keep playing similar songs from your library") worth shipping.
 */
@Composable
fun QueuePlaybackModeRow(
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onShuffleClick) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(R.string.cd_shuffle),
                tint = if (shuffleEnabled) TextPrimary else TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onRepeatClick) {
            Icon(
                imageVector = if (repeatMode == RepeatMode.One) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = stringResource(R.string.cd_repeat),
                tint = if (repeatMode != RepeatMode.Off) TextPrimary else TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Off -> All -> One -> Off. */
fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.Off -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}
