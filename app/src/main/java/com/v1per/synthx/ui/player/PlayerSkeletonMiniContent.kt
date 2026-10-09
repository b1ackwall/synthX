package com.v1per.synthx.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.DragResistantIconButton
// DEAD CODE: accent left the player; the mini content follows LocalPlayerContentColors. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Spacing
// DEAD CODE: the mini content follows LocalPlayerContentColors instead of fixed text tokens. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.ui.theme.TextPrimary
// import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Mini content: title/artist + play/pause, PlayerMiniBar.kt's Row minus the art (art is the
 * single shared element positioned separately by [PlayerSheetSkeleton], already at the right
 * spot at progress 0). Pinned to the sheet's own top edge at a fixed CollapsedPlayerHeight,
 * exactly like the old PlayerMiniBar's Modifier.align(Alignment.TopCenter) — as sheetHeight
 * grows the sheet's top edge rises with it, carrying this row along while it fades out.
 */
@Composable
fun BoxScope.PlayerSkeletonMiniContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    miniContentAlpha: Float,
    miniContentEnabled: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val contentColors = LocalPlayerContentColors.current ?: PlayerContentColors.Light
    Row(
        modifier = modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(height)
            .alpha(miniContentAlpha)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Reserves exactly the footprint the 44dp mini art occupies at progress
        // 0 — the real art is a separately positioned overlay (artOffsetX/Y
        // above), not a child of this Row, so this Spacer exists purely to push
        // the text/button to the same X the old Row's art-then-content
        // arrangement produced.
        Spacer(modifier = Modifier.width(44.dp))
        Spacer(modifier = Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                // DEAD CODE: the mini title follows the player's shared content color. Delete in a cleanup pass. Ask  before deleting.
                // color = TextPrimary,
                color = contentColors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                // DEAD CODE: the mini artist follows the player's shared content color. Delete in a cleanup pass. Ask  before deleting.
                // color = TextSecondary,
                color = contentColors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DragResistantIconButton(
            onClick = onPlayPauseClick,
            enabled = miniContentEnabled,
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    // DEAD CODE: the mini spinner follows the player's shared content color. Delete in a cleanup pass. Ask  before deleting.
                    // color = TextSecondary,
                    color = contentColors.content,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) {
                        stringResource(R.string.cd_pause)
                    } else {
                        stringResource(R.string.cd_play)
                    },
                    // DEAD CODE: accent left the player; play/pause uses the shared content color in both states. Delete in a cleanup pass. Ask  before deleting.
                    // tint = if (isPlaying) Accent else TextPrimary,
                    tint = contentColors.content,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
