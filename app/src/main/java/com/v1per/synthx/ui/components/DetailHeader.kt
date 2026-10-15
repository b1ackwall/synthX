package com.v1per.synthx.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Edge length of a detail screen's hero art. Fixed: the size is part of the playlist art cache
 * key and the album cover's decode size, so it must not change while the screen scrolls.
 */
val DetailHeroSize: Dp = 240.dp

/** "42 songs · 2 hr 41 min" under a detail screen's hero. */
@Composable
fun DetailStatsLine(
    songCount: Int,
    totalDurationMs: Long,
    modifier: Modifier = Modifier,
) {
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
 * Play and Shuffle, equal width. Both are one-shot actions: neither reads nor shows the player's
 * shuffle state. [enabled] is false when there is nothing playable.
 */
@Composable
fun DetailPlayShuffleRow(
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.ItemSpacing),
    ) {
        DetailActionButton(
            label = stringResource(R.string.action_play),
            icon = Icons.Filled.PlayArrow,
            enabled = enabled,
            onClick = onPlay,
            colors = ButtonDefaults.buttonColors(
                containerColor = TextPrimary,
                contentColor = Background,
                disabledContainerColor = TextPrimary.copy(alpha = Alpha.Disabled),
                disabledContentColor = Background,
            ),
            border = null,
            modifier = Modifier.weight(1f).testTag(DETAIL_PLAY_BUTTON_TAG),
        )
        DetailActionButton(
            label = stringResource(R.string.action_shuffle),
            icon = Icons.Filled.Shuffle,
            enabled = enabled,
            onClick = onShuffle,
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceVariant,
                contentColor = TextPrimary,
                disabledContainerColor = SurfaceVariant,
                disabledContentColor = TextPrimary.copy(alpha = Alpha.Disabled),
            ),
            border = BorderStroke(DetailButtonBorderWidth, Hairline),
            modifier = Modifier.weight(1f).testTag(DETAIL_SHUFFLE_BUTTON_TAG),
        )
    }
}

@Composable
private fun DetailActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    colors: ButtonColors,
    border: BorderStroke?,
    modifier: Modifier,
) {
    // enabled=false drops the click and marks the node disabled for accessibility; the button
    // stays composed so the row keeps its shape.
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = colors,
        border = border,
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.ItemSpacing),
        modifier = modifier,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Matches the 1dp hairline the rest of the app draws borders and dividers with. */
private val DetailButtonBorderWidth: Dp = 1.dp

const val DETAIL_STATS_LINE_TAG = "detail_stats_line"
const val DETAIL_PLAY_BUTTON_TAG = "detail_play_button"
const val DETAIL_SHUFFLE_BUTTON_TAG = "detail_shuffle_button"
