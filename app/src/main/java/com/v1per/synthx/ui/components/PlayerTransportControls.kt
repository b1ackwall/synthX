package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.TextPrimary

// Material standard disabled-content alpha (Material 3 ships no ContentAlpha token) — same
// value and same reasoning as SongRow's DisabledContentAlpha.
private const val DisabledContentAlpha = 0.38f

/** Prev / play-pause / next transport row. */
@Composable
fun PlayerTransportControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val previousEnabled = enabled && hasPrevious
    val nextEnabled = enabled && hasNext

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(2f))
        IconButton(
            onClick = onPreviousClick,
            modifier = Modifier.size(72.dp),
            enabled = previousEnabled,
        ) {
            Icon(
                imageVector = Icons.Filled.FastRewind,
                contentDescription = stringResource(R.string.cd_previous_track),
                tint = TextPrimary,
                modifier = Modifier
                    .size(56.dp)
                    .alpha(if (previousEnabled) 1f else DisabledContentAlpha),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier.size(96.dp),
            enabled = enabled,
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = TextPrimary,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) {
                        stringResource(R.string.cd_pause)
                    } else {
                        stringResource(R.string.cd_play)
                    },
                    tint = if (isPlaying) Accent else TextPrimary,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onNextClick,
            modifier = Modifier.size(72.dp),
            enabled = nextEnabled,
        ) {
            Icon(
                imageVector = Icons.Filled.FastForward,
                contentDescription = stringResource(R.string.cd_next_track),
                tint = TextPrimary,
                modifier = Modifier
                    .size(56.dp)
                    .alpha(if (nextEnabled) 1f else DisabledContentAlpha),
            )
        }
        Spacer(modifier = Modifier.weight(2f))
    }
}
