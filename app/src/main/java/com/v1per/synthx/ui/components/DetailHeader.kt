package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Edge length of a detail screen's hero art. Fixed: the size is part of the playlist art cache
 * key and the album cover's decode size, so it must not change while the screen scrolls.
 */
val DetailHeroSize: Dp = 240.dp

/** "42 songs · 2 hr 41 min" under a detail screen's hero. */
@Composable
fun DetailStatsLine(songCount: Int, totalDurationMs: Long, modifier: Modifier = Modifier) {
    Text(
        text = formatSongStats(songCount, totalDurationMs, rememberSongStatsStrings()),
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding)
            .testTag(DETAIL_STATS_LINE_TAG),
    )
}

/**
 * Play and Shuffle: two Compact buttons with a label and no icon, side by side and centred in the
 * row whenever both fit (see [SynthXButtonPair]). Both are one-shot actions: neither reads nor
 * shows the player's shuffle state. [enabled] is false when there is nothing playable; the buttons
 * then draw at the disabled alpha, ignore clicks and stay composed, so the header keeps its shape.
 */
@Composable
fun DetailPlayShuffleRow(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    SynthXButtonPair(
        primary = {
            SynthXPrimaryButton(
                onClick = onPlay,
                width = SynthXButtonWidth.Compact,
                modifier = Modifier.testTag(DETAIL_PLAY_BUTTON_TAG),
                enabled = enabled,
            ) {
                Text(text = stringResource(R.string.action_play))
            }
        },
        secondary = {
            SynthXSecondaryButton(
                onClick = onShuffle,
                width = SynthXButtonWidth.Compact,
                modifier = Modifier.testTag(DETAIL_SHUFFLE_BUTTON_TAG),
                enabled = enabled,
            ) {
                Text(text = stringResource(R.string.action_shuffle))
            }
        },
        modifier = modifier.padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
    )
}

const val DETAIL_STATS_LINE_TAG = "detail_stats_line"
const val DETAIL_PLAY_BUTTON_TAG = "detail_play_button"
const val DETAIL_SHUFFLE_BUTTON_TAG = "detail_shuffle_button"
