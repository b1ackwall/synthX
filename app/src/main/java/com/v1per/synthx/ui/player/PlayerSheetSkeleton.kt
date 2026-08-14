package com.v1per.synthx.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.PlayerSheetValue
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SurfaceVariant
import kotlinx.coroutines.launch

/**
 * Empty skeleton stand-in for [com.v1per.synthx.ui.components.PlayerSheet]: same single
 * AnchoredDraggableState morph between mini/full player, wired to real [PlaybackViewModel]
 * state, but rendering blank surfaces only. The old PlayerSheet and its child composables
 * are untouched and unused — swap the BottomChromeOverlay call site back to restore them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerSheetSkeleton(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    onProgressChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }
    // Same tuning as the current PlayerSheet — higher friction lets the fling decelerate
    // before handing off to the snap spring.
    val decayAnimationSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()

        val draggableState = remember {
            AnchoredDraggableState(
                initialValue = PlayerSheetValue.Collapsed,
                positionalThreshold = { totalDistance -> totalDistance * 0.4f },
                velocityThreshold = { with(density) { 200.dp.toPx() } },
                snapAnimationSpec = Motion.SpringStandard,
                decayAnimationSpec = decayAnimationSpec,
            )
        }

        BackHandler(enabled = draggableState.currentValue == PlayerSheetValue.Expanded) {
            scope.launch { draggableState.animateTo(PlayerSheetValue.Collapsed) }
        }

        LaunchedEffect(availableHeightPx) {
            if (availableHeightPx > 0f) {
                draggableState.updateAnchors(
                    DraggableAnchors {
                        PlayerSheetValue.Collapsed at availableHeightPx - collapsedHeightPx
                        PlayerSheetValue.Expanded at 0f
                    },
                )
            }
        }

        // 0f = fully collapsed, 1f = fully expanded. Guards the pre-initialization NaN
        // AnchoredDraggableState.offset reports before the first updateAnchors() call lands.
        val progress by remember(availableHeightPx, collapsedHeightPx) {
            derivedStateOf {
                val offset = draggableState.offset
                if (offset.isNaN()) return@derivedStateOf 0f
                val collapsedAnchor = availableHeightPx - collapsedHeightPx
                if (collapsedAnchor <= 0f) 0f
                else ((collapsedAnchor - offset) / collapsedAnchor).coerceIn(0f, 1f)
            }
        }

        onProgressChanged(progress)

        val sheetHeight = lerpDp(CollapsedPlayerHeight, maxHeight, progress)
        val cornerRadius = lerpDp(0.dp, 24.dp, progress)
        val bottomInset = lerpDp(BottomNavHeight, 0.dp, progress)
        // No visible content, but a subtle structural-color shift confirms mini vs. expanded
        // state at a glance without touching the accent color or any real UI.
        val surfaceColor = lerp(Surface, SurfaceVariant, progress)

        Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                    .background(surfaceColor)
                    // Single unified detector driving draggableState directly — replaces the
                    // anchoredDraggable + dragResistantClickable pairing, which silently
                    // swallowed touches when co-located on one node (anchoredDraggable's
                    // drag-start detector permanently aborts the moment it sees a consumed
                    // pointer change, and dragResistantClickable's shielding logic has no live
                    // ancestor left to hand a real drag off to once released). One
                    // awaitEachGesture loop now arbitrates tap vs. drag itself: down + the same
                    // awaitVerticalTouchSlopOrCancellation Foundation's own draggable/
                    // anchoredDraggable use internally decide whether this is a tap (pointer up
                    // before slop) or a drag (slop crossed) — with no second detector, there's
                    // no consumption hand-off to fail.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            var overSlopDelta = 0f
                            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                change.consume()
                                overSlopDelta = overSlop
                            }

                            if (drag == null) {
                                // Pointer lifted before crossing touch slop — a tap. Toggle
                                // the same AnchoredDraggableState the drag path drives.
                                val target = if (draggableState.currentValue == PlayerSheetValue.Collapsed) {
                                    PlayerSheetValue.Expanded
                                } else {
                                    PlayerSheetValue.Collapsed
                                }
                                scope.launch { draggableState.animateTo(target) }
                            } else {
                                // Slop crossed — this is a drag. Feed the post-slop offset (the
                                // same value detectVerticalDragGestures applies as its first
                                // onVerticalDrag delta) and then every subsequent delta straight
                                // into the state via dispatchRawDelta, exactly as
                                // anchoredDraggable's own internal drag loop would.
                                velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                                draggableState.dispatchRawDelta(overSlopDelta)
                                verticalDrag(drag.id) { change ->
                                    draggableState.dispatchRawDelta(change.positionChange().y)
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                }
                                // Up (or cancel) reached — let the state settle to its nearest
                                // anchor using the same velocity/positional threshold logic
                                // settle() always used, seeded with the real release velocity.
                                val velocity = velocityTracker.calculateVelocity().y
                                scope.launch { draggableState.settle(velocity) }
                            }
                        }
                    },
            )
        }
    }
}
