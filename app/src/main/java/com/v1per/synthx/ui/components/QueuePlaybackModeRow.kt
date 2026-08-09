package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
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
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Shuffle / Repeat / Autoplay row. Shuffle and Repeat tint [Accent] when active — the same
 * "tint marks the active state" convention as the play/pause icon elsewhere in the player,
 * rather than swapping to an "_on" icon glyph that material-icons-extended doesn't ship.
 * Autoplay is a genuine placeholder: Apple Music's Autoplay pulls from a streaming catalog,
 * which has no equivalent against a local library, so it carries no state or behavior.
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
                tint = if (shuffleEnabled) Accent else TextSecondary,
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
                tint = if (repeatMode != RepeatMode.Off) Accent else TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = {}) {
            Icon(
                imageVector = Icons.Filled.AllInclusive,
                contentDescription = stringResource(R.string.cd_autoplay),
                tint = TextSecondary,
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
