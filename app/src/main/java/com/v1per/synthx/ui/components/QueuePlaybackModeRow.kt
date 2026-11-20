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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.v1per.synthx.R
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.player.LocalPlayerContentColors
import com.v1per.synthx.ui.player.PlayerContentColors
import com.v1per.synthx.ui.theme.IconSizes
import com.v1per.synthx.ui.theme.Spacing

/**
 * Shuffle / Repeat row. Both tint the player's content color when active (vs. the same color
 * at Alpha.ToggleOff, [PlayerContentColors.off], when inactive) — the same "tint marks the
 * active state" convention as the queue button, rather than swapping to an "_on" icon glyph
 * that material-icons-extended doesn't ship. The accent color is deliberately not used here.
 *
 * Accessibility services get the state the tint shows: Shuffle is a switch that is on or off, and
 * Repeat states Off, All or One after its name. Both are added as semantics over
 * [DragResistantIconButton], so taps and the accessibility click keep its drag-resistant handling.
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
    val contentColors = LocalPlayerContentColors.current ?: PlayerContentColors.Light
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        val shuffleState = if (shuffleEnabled) ToggleableState.On else ToggleableState.Off
        DragResistantIconButton(
            onClick = onShuffleClick,
            // Outermost, so this role takes the place of the button's own Role.Button.
            modifier = Modifier.semantics {
                role = Role.Switch
                toggleableState = shuffleState
            },
            enabled = enabled,
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(R.string.cd_shuffle),
                tint = if (shuffleEnabled) contentColors.content else contentColors.off,
                modifier = Modifier.size(IconSizes.Standard),
            )
        }
        val repeatState = stringResource(
            when (repeatMode) {
                RepeatMode.Off -> R.string.cd_repeat_off
                RepeatMode.All -> R.string.cd_repeat_all
                RepeatMode.One -> R.string.cd_repeat_one
            },
        )
        DragResistantIconButton(
            onClick = onRepeatClick,
            modifier = Modifier.semantics { stateDescription = repeatState },
            enabled = enabled,
        ) {
            Icon(
                imageVector = if (repeatMode == RepeatMode.One) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = stringResource(R.string.cd_repeat),
                tint = if (repeatMode != RepeatMode.Off) contentColors.content else contentColors.off,
                modifier = Modifier.size(IconSizes.Standard),
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
