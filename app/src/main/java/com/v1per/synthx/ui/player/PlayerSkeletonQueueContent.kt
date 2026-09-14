package com.v1per.synthx.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.components.MoreOptionsButton
import com.v1per.synthx.ui.components.QueueList
import com.v1per.synthx.ui.components.QueuePlaybackModeRow
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.collections.immutable.toPersistentList

private val ControlsMaskFadeHeight = 48.dp

/**
 * Queue-mode header + Up Next list: title/artist bound to real, live song data, trailing
 * button is [MoreOptionsButton] (same shared composable PlayerSkeletonExpandedContent's title
 * row already uses, no new provider needed). A leading spacer reserves the art's own footprint
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
 * straight into [QueueList]'s own `state` param. [queueScrollConnection] is that same
 * PlayerSheetSkeleton.kt's `QueueModeState.queueScrollConnection`, applied here via
 * `Modifier.nestedScroll` on [QueueList]'s modifier — it observes this list's drag deltas
 * directly (regardless of whether the list itself has room to scroll) to derive
 * `controlsVisible` for `PlayerSkeletonControlsOverlay`.
 *
 * [QueueList] reports a swipe-to-remove as an index into [upcomingQueue]; this composable
 * resolves it to a song id here, while the list is still in hand, and hands [onQueueRemove]
 * that id instead. Everything below the UI layer is identity-based for the same reason — see
 * [com.v1per.synthx.playback.PlaybackEngine.removeQueueItem]'s doc comment.
 *
 * Only [QueueList] is conditionally composed (`queueProgressValue > 0f`), not just
 * alpha-faded: an always-present [QueueList] would stay hit-testable even at alpha 0, silently
 * intercepting taps/drags meant for the sheet's own gesture detector while in player mode
 * (queue mode never entered) — the header Row and [QueuePlaybackModeRow] carry no such
 * scroll/drag surface, so they stay always-composed and driven by alpha/offset alone, same as
 * the header always has. Since [queueProgressValue] animates continuously from 0f on the
 * first frame of the queue-button tap (see [Motion.DurationNormal]'s `Animatable.animateTo` in
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
    queueScrollConnection: NestedScrollConnection,
    expandedContentAlpha: Float,
    queueProgressValue: Float,
    expandedContentEnabled: Boolean,
    queueModeArtSize: Dp,
    upcomingQueue: List<QueueSlot>,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onQueueSongClick: (slotId: String) -> Unit,
    onQueueMove: (slotId: String, toIndex: Int) -> Unit,
    onQueueRemove: (songId: String) -> Unit,
    controlsOverlayHeight: Dp,
    controlsOverlayAlpha: Float,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val queueContentAlpha = expandedContentAlpha * queueProgressValue
    val queueContentEnabled = expandedContentEnabled && queueProgressValue > 0.5f

    // Keyed on upcomingQueue only (not queueProgressValue) so this only re-runs when the
    // queue's actual contents change — this composable itself re-executes every frame of
    // the queue-mode/sheet-drag animation because queueProgressValue is a real parameter,
    // and an inline .toPersistentList() here would otherwise reallocate/re-copy the whole
    // list on every one of those frames for no reason.
    val persistentUpcomingQueue = remember(upcomingQueue) { upcomingQueue.toPersistentList() }

    // Slide-up, matching PlayerSkeletonControlsOverlay's: this block rises
    // [ContentSlideDistance] into place as it fades in. Driven by [queueContentAlpha] — the
    // same product the .alpha() calls below use — so it responds to both axes that make this
    // content visible at all: the sheet's collapse/expand drag (via expandedContentAlpha) and
    // the queue-mode toggle (via queueProgressValue). Being a product, it only reaches 0dp
    // once both terms are 1, which is the same point the content is fully opaque.
    //
    // Bidirectional for the same reason the overlay's is — a pure function of live values
    // with no animation state of its own, so every direction reverses on its own: dragging
    // the sheet open/closed while in queue mode moves expandedContentAlpha, tapping the queue
    // button in/out animates queueProgressValue, and the offset simply tracks whichever
    // changed. Both children take the identical offset, so the header-to-list gap is constant
    // throughout the slide rather than the two drifting apart.
    val queueContentSlideOffset = lerpDp(ContentSlideDistance, 0.dp, queueContentAlpha)
    // Match the controls' placement, which slides on expansion alone and compensates for
    // the sheet's bottom inset. Queue content has a separate slide during its crossfade.
    val controlsOverlayOffsetY = lerpDp(ContentSlideDistance, 0.dp, expandedContentAlpha) + bottomInset

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                // Resting anchor plus the slide, summed into the single offset() call rather
                // than chained as two: Modifier.offset composes additively either way, so one
                // layout modifier node expresses it exactly and reads as "where it lives,
                // plus how far it still has to travel".
                .offset(y = artOffsetYQueue + queueContentSlideOffset)
                .fillMaxWidth()
                .height(headerRegionHeight)
                // Left margin is 32dp (Spacing.xl), a deliberate deviation from the 16dp
                // Spacing.HorizontalPadding kept on the right — must stay in sync with
                // QueueArtPositioning.kt's artOffsetXQueue, which the art itself uses.
                .padding(start = Spacing.xl, end = Spacing.HorizontalPadding)
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

        if (queueProgressValue > 0f && upcomingQueue.isEmpty()) {
            // fillMaxHeight() here would center within the whole remaining sheet height,
            // including the ~300dp the fixed PlayerSkeletonControlsOverlay draws over at the
            // bottom — unlike QueueList's LazyColumn (which reserves that space via its own
            // contentPadding), a static centered Box has no equivalent, so the safe span has
            // to be measured explicitly: this maxHeight comes from BoxWithConstraints above
            // (the same live constraint QueueList/the overlay ultimately resolve against),
            // not a hardcoded constant, since it varies by device/sheet size.
            val emptyStateHeight = (maxHeight - queueListOffsetY - controlsOverlayHeight)
                .coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .offset(y = queueListOffsetY + queueContentSlideOffset)
                    .fillMaxWidth()
                    .height(emptyStateHeight)
                    .alpha(queueContentAlpha),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.empty_queue_no_upcoming),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )
            }
        } else if (queueProgressValue > 0f) {
            QueueList(
                queue = persistentUpcomingQueue,
                onSongClick = onQueueSongClick,
                onMove = onQueueMove,
                onRemove = { index -> upcomingQueue.getOrNull(index)?.let { onQueueRemove(it.slotId) } },
                state = queueListState,
                enabled = queueContentEnabled,
                contentPadding = PaddingValues(bottom = controlsOverlayHeight),
                modifier = Modifier
                    // Same summed anchor-plus-slide as the header Column above.
                    .offset(y = queueListOffsetY + queueContentSlideOffset)
                    // Observes drag deltas dispatched by QueueList's own LazyColumn (nested
                    // scroll dispatch reports these regardless of whether the list can
                    // actually scroll) to drive the controls overlay's gesture-direction
                    // visibility — see QueueModeState.queueScrollConnection's doc comment.
                    .nestedScroll(queueScrollConnection)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = queueContentAlpha
                        // DstIn must multiply only the queue pixels, never the blobs or
                        // sibling controls. Keep its existing crossfade in the same layer.
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithCache {
                        // The list measures at sheet height, then is offset downward. Its
                        // measured bottom is below the sheet's visible bottom: subtract
                        // the list origin to express the controls' top in list coordinates.
                        val overlayHeightPx = controlsOverlayHeight.toPx().coerceAtMost(size.height)
                        val controlsTop = size.height - overlayHeightPx + controlsOverlayOffsetY.toPx()
                        val fadeEnd = controlsTop - (queueListOffsetY + queueContentSlideOffset).toPx()
                        val strength = controlsOverlayAlpha.coerceIn(0f, 1f)
                        val mask = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - strength)),
                            startY = fadeEnd - ControlsMaskFadeHeight.toPx(),
                            endY = fadeEnd,
                        )
                        onDrawWithContent {
                            drawContent()
                            // An opaque mask preserves alpha; transparent removes it.
                            // Release only the lower region, not the whole list's opacity.
                            if (strength > 0f) {
                                drawRect(brush = mask, blendMode = BlendMode.DstIn)
                            }
                        }
                    },
            )
        }
    }
}
