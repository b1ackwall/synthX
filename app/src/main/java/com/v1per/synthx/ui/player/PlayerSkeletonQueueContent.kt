package com.v1per.synthx.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.components.MoreOptionsButton
import com.v1per.synthx.ui.components.QueueList
import com.v1per.synthx.ui.components.QueuePlaybackModeRow
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Queue-mode header + Up Next list: header is PlayerQueueModeContent.kt's QueueModeHeader
 * reproduced here — title/artist bound to real, live song data, trailing button now the real
 * MoreOptionsButton (same internal composable PlayerSkeletonExpandedContent's title row
 * already uses, no new provider needed). Positioned at the same artOffsetYQueue anchor the
 * art animates toward; a leading spacer reserves the art's own footprint (same technique the
 * mini content Row uses), since the art itself is the separately positioned shared element.
 *
 * [queueListOffsetY] anchors the shuffle/repeat row + Up Next list below the header, at a
 * fixed resting position (not lerped) — same convention as the header's own offset: both only
 * become visible once faded in via [expandedContentAlpha] * [queueProgressValue], by which
 * point any lerp animation has already settled, so there is nothing to interpolate.
 *
 * Removal is out of scope entirely — [QueueRow] has no swipe gesture at all, and neither
 * [QueueList] nor this composable carries an onRemove hook. A separate, later task.
 */
@Composable
fun PlayerSkeletonQueueContent(
    song: Song,
    artOffsetYQueue: Dp,
    queueListOffsetY: Dp,
    expandedContentAlpha: Float,
    queueProgressValue: Float,
    expandedContentEnabled: Boolean,
    queueModeArtSize: Dp,
    upcomingQueue: List<Song>,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onQueueSongClick: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    controlsOverlayHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val queueContentAlpha = expandedContentAlpha * queueProgressValue

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset(y = artOffsetYQueue)
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding)
                .alpha(queueContentAlpha),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(queueModeArtSize))
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MoreOptionsButton(song = song, enabled = expandedContentEnabled)
            }
        }

        Column(
            modifier = Modifier
                .offset(y = queueListOffsetY)
                .fillMaxWidth()
                .fillMaxHeight()
                .alpha(queueContentAlpha),
        ) {
            QueuePlaybackModeRow(
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                onShuffleClick = onShuffleClick,
                onRepeatClick = onRepeatClick,
                enabled = expandedContentEnabled,
                modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
            )
            QueueList(
                queue = upcomingQueue,
                onSongClick = onQueueSongClick,
                onMove = onQueueMove,
                enabled = expandedContentEnabled,
                contentPadding = PaddingValues(bottom = controlsOverlayHeight),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}
