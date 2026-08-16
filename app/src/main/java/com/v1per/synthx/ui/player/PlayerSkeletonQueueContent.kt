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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
 * already uses, no new provider needed). A leading spacer reserves the art's own footprint
 * (same technique the mini content Row uses), since the art itself is the separately
 * positioned shared element.
 *
 * The header Row and [QueuePlaybackModeRow] now share one real Column, bounded to
 * [headerRegionHeight] (see that constant's doc comment in PlayerSheetSkeleton.kt), anchored
 * at the same artOffsetYQueue anchor the art animates toward. A single `weight(1f)` [Spacer]
 * sits above the header Row, absorbing all of this Column's slack — both the header Row and
 * [QueuePlaybackModeRow] below it are flush against each other and against the Column's
 * bottom edge, with no spacer between them. The only breathing room between them is
 * [QueuePlaybackModeRow]'s own internal `Spacing.sm` vertical padding, nothing added here.
 * [queueListOffsetY] is where [QueueList] starts, right after this Column's fixed-height
 * region ends.
 *
 * [queueListState] is hoisted up in PlayerSheetSkeleton.kt (not owned here) and passed
 * straight into [QueueList]'s own `state` param — that same instance is what
 * PlayerSheetSkeleton.kt reads to derive `controlsVisible` for
 * `PlayerSkeletonControlsOverlay`, so the overlay's scroll-driven hide/show tracks this exact
 * list's scroll position.
 *
 * Removal is out of scope entirely — [QueueRow] has no swipe gesture at all, and neither
 * [QueueList] nor this composable carries an onRemove hook. A separate, later task.
 *
 * Only [QueueList] is conditionally composed (`queueProgressValue > 0f`), not just
 * alpha-faded: an always-present [QueueList] would stay hit-testable even at alpha 0, silently
 * intercepting taps/drags meant for the sheet's own gesture detector while in player mode
 * (queue mode never entered) — the header Row and [QueuePlaybackModeRow] carry no such
 * scroll/drag surface, so they stay always-composed and alpha-only, same as the header always
 * has. Since [queueProgressValue] animates continuously from 0f on the first frame of the
 * queue-button tap (see [Motion.DurationNormal]'s `Animatable.animateTo` in
 * PlayerSheetSkeleton.kt), entering composition at `> 0f` puts [QueueList] in the tree on that
 * very first frame — indistinguishable from an always-composed, alpha-only list visually, just
 * no longer hit-testable at exactly 0f.
 *
 * [queueContentEnabled] mirrors [expandedContentEnabled]'s own `> 0.5f` convention, but on
 * [queueProgressValue] instead of the collapse/expand `progress` axis — [expandedContentEnabled]
 * alone is insensitive to whether queue mode is actually active, so a tap during the
 * queue-mode-entry animation (still visually mid-fade) could otherwise fire a real queue
 * action before the content is meaningfully visible.
 */
@Composable
fun PlayerSkeletonQueueContent(
    song: Song,
    artOffsetYQueue: Dp,
    queueListOffsetY: Dp,
    headerRegionHeight: Dp,
    queueListState: LazyListState,
    surfaceColor: Color,
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
    val queueContentEnabled = expandedContentEnabled && queueProgressValue > 0.5f

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .offset(y = artOffsetYQueue)
                .fillMaxWidth()
                .height(headerRegionHeight)
                .padding(horizontal = Spacing.HorizontalPadding)
                .alpha(queueContentAlpha),
        ) {
            Spacer(modifier = Modifier.weight(1f))

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
                MoreOptionsButton(song = song, enabled = queueContentEnabled)
            }

            QueuePlaybackModeRow(
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                onShuffleClick = onShuffleClick,
                onRepeatClick = onRepeatClick,
                enabled = queueContentEnabled,
            )
        }

        if (queueProgressValue > 0f) {
            QueueList(
                queue = upcomingQueue,
                onSongClick = onQueueSongClick,
                onMove = onQueueMove,
                surfaceColor = surfaceColor,
                state = queueListState,
                enabled = queueContentEnabled,
                contentPadding = PaddingValues(bottom = controlsOverlayHeight),
                modifier = Modifier
                    .offset(y = queueListOffsetY)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .alpha(queueContentAlpha),
            )
        }
    }
}
