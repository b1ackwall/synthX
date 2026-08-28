package com.v1per.synthx.ui.components

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextTertiary
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

/**
 * Swipe-to-remove anchors. [Settled] is the resting position; the two directional anchors sit a
 * full row width to either side, so the drag is never capped partway.
 */
private enum class QueueRowSwipe { StartToEnd, Settled, EndToStart }

/**
 * Release velocity above which a swipe dismisses no matter how far it actually travelled —
 * AnchoredDraggableState skips [QueueRowSwipe]'s positional threshold entirely once this is
 * cleared (see `computeTarget`). Material3's own SwipeToDismissBox hardcodes 125.dp/s, barely
 * above Android's 50.dp/s minimum-fling velocity, so nearly any moving release dismisses.
 * 1200.dp/s is roughly "crosses a whole row in a third of a second" — above the few hundred
 * dp/s an unhurried drag-and-lift releases at, well below a genuine flick.
 */
private val SwipeVelocityThreshold = 1200.dp

/**
 * One Up Next row: tap to jump to and play, drag the trailing handle to reorder, swipe either
 * way to remove. The handle is the only part [dragHandleModifier] is applied to — the rest of
 * the row must never start a reorder-drag, only [onClick] or the swipe.
 *
 * The currently-playing song is never rendered as a row here (it lives in
 * PlayerSkeletonQueueContent's Queue-mode header instead), so there is no "is this the
 * playing row" state to track any more.
 *
 * The drag handle is always enabled — queue ordering is fully app-managed, so reordering
 * is safe regardless of shuffle state, see
 * [com.v1per.synthx.playback.ExoPlayerEngine.moveQueueItem]'s doc comment.
 *
 * [onMoveUp]/[onMoveDown] give TalkBack/switch-access users a non-gesture equivalent of the
 * drag reorder — the handle's `onClick` is a no-op (it exists only to host
 * [dragHandleModifier]'s gesture detection), so without these the handle would announce as an
 * interactive button that does nothing when activated. Null when the move isn't possible
 * (already first/last), matching how the drag itself has nowhere to go at either end. The
 * handle carries a third action for [onRemove] on the same principle — it's the non-gesture
 * equivalent of the swipe, which those users likewise can't perform.
 *
 * [queue] is the authoritative upcoming list this row belongs to — read only by the
 * self-healing reset below, which needs to know whether [song] genuinely left the queue after
 * a confirmed dismissal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueRow(
    song: Song,
    queue: ImmutableList<Song>,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    surfaceColor: Color,
    dragHandleModifier: Modifier,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val density = LocalDensity.current

    // The removal watcher below lives in a no-key LaunchedEffect, so it would otherwise keep
    // calling whichever onRemove existed when this row first entered composition — and
    // QueueList hands every row a freshly-built closure over its current index on each
    // recomposition. rememberUpdatedState is what makes that watcher read the live one.
    val currentOnRemove = rememberUpdatedState(onRemove)

    val swipeState = remember {
        AnchoredDraggableState(
            initialValue = QueueRowSwipe.Settled,
            // Release past half the row's own width confirms; release before it reverts.
            positionalThreshold = { totalDistance -> totalDistance * 0.5f },
            velocityThreshold = { with(density) { SwipeVelocityThreshold.toPx() } },
            snapAnimationSpec = Motion.SpringStandard,
            decayAnimationSpec = exponentialDecay(),
        )
    }

    // settledValue — not confirmValueChange — is the "this row really was dismissed" signal.
    // AnchoredDraggableState invokes confirmValueChange from AnchoredDragScope.updateIfNeeded,
    // i.e. mid-drag the instant the offset crosses halfway, so removing from there would fire
    // for a drag that the user then pulled back and released short of the threshold.
    // settledValue only advances once the dismiss animation has actually come to rest.
    LaunchedEffect(swipeState) {
        snapshotFlow { swipeState.settledValue }
            .collect { settled ->
                if (settled != QueueRowSwipe.Settled) currentOnRemove.value()
            }
    }

    // Self-heal a confirmed dismissal whose removal never landed, which would otherwise leave
    // this row parked off-screen but still occupying its slot. Keyed on [queue] alone: the
    // authoritative list changing is the genuine completion signal, and re-running this on the
    // swipe state itself would check before the removal's async round trip had a chance to
    // land. A successful removal disposes this row before the check can matter.
    LaunchedEffect(queue) {
        if (swipeState.settledValue != QueueRowSwipe.Settled && queue.any { it.id == song.id }) {
            swipeState.animateTo(QueueRowSwipe.Settled)
        }
    }

    // The reveal icon sits on the side the row is being dragged away from. derivedStateOf so
    // the per-frame offset only triggers recomposition on an actual side flip.
    val revealAlignment by remember {
        derivedStateOf {
            val offset = swipeState.offset
            if (!offset.isNaN() && offset > 0f) Alignment.CenterStart else Alignment.CenterEnd
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Anchors are the row's own measured width, so the drag runs the full width in
            // either direction. Set from the layout phase, as updateAnchors requires.
            .onSizeChanged { size ->
                val width = size.width.toFloat()
                swipeState.updateAnchors(
                    DraggableAnchors {
                        QueueRowSwipe.StartToEnd at width
                        QueueRowSwipe.Settled at 0f
                        QueueRowSwipe.EndToStart at -width
                    },
                )
            }
            // Deliberately no `enabled` argument: AnchoredDraggableState already serialises
            // drag/settle/reset through its own MutatorMutex, and changing that parameter
            // rebuilds the whole gesture-detection node. Removal is gated at the callback
            // level in QueueList instead, same as onSongClick/onMove.
            .anchoredDraggable(state = swipeState, orientation = Orientation.Horizontal),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Error),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                // Decorative: it sits behind the row and describes a gesture TalkBack can't
                // perform, so a description here would only add a phantom node per row. The
                // actionable equivalent is the drag handle's Remove custom action below.
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(revealAlignment)
                    .padding(horizontal = Spacing.md),
            )
        }

        Row(
            modifier = Modifier
                .offset {
                    // NaN until the first updateAnchors lands.
                    val x = swipeState.offset
                    IntOffset(if (x.isNaN()) 0 else x.roundToInt(), 0)
                }
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(surfaceColor)
                .clickable(onClick = onClick)
                // start = Spacing.md, not Spacing.HorizontalPadding: combines with
                // SongIdentity's own internal Spacer(Spacing.md) to reach the 32dp
                // (Spacing.xl) queue-mode left margin — must stay in sync with
                // PlayerSkeletonQueueContent's header padding and QueueArtPositioning.kt's
                // artOffsetXQueue.
                .padding(
                    start = Spacing.md,
                    top = Spacing.sm,
                    end = Spacing.HorizontalPadding,
                    bottom = Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongIdentity(
                song = song,
                modifier = Modifier.weight(1f),
            )

            val moveUpLabel = stringResource(R.string.action_move_up)
            val moveDownLabel = stringResource(R.string.action_move_down)
            val removeLabel = stringResource(R.string.action_remove_from_queue)
            IconButton(
                onClick = {},
                modifier = dragHandleModifier
                    .padding(end = Spacing.sm)
                    .semantics {
                        customActions = listOfNotNull(
                            onMoveUp?.let {
                                CustomAccessibilityAction(moveUpLabel) { it(); true }
                            },
                            onMoveDown?.let {
                                CustomAccessibilityAction(moveDownLabel) { it(); true }
                            },
                            // Same callback the swipe reaches, invoked directly: there is no
                            // gesture to settle, so the row just disappears when the removal
                            // lands. Listed last — destructive action after the reorders.
                            CustomAccessibilityAction(removeLabel) { onRemove(); true },
                        )
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.cd_drag_handle),
                    tint = TextTertiary,
                )
            }
        }
    }
}
