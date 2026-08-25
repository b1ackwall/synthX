package com.v1per.synthx.ui.player.v2

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
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SurfaceVariant
import kotlinx.coroutines.launch

/**
 * The two states this skeleton morphs between. Deliberately two — no Queue state. Queue
 * mode is a later stage of the rebuild; adding a third value now would mean the drag
 * anchors, the progress derivation and every lerp below stop being a single 0..1 axis.
 *
 * Structurally identical to [com.v1per.synthx.ui.components.PlayerSheetValue], and declared
 * fresh anyway rather than reused: that enum belongs to the old player's still-live call
 * graph, and v2 is meant to share nothing with it but the theme tokens and
 * CollapsedPlayerHeight.
 */
enum class PlayerV2State { Collapsed, Expanded }

/**
 * Stage 1.1 of the player rebuild — see docs/player-rebuild-design.md for the eventual
 * target architecture.
 *
 * This is deliberately, aggressively incomplete: a blank dark surface that morphs between a
 * 64dp mini bar and a full-height sheet, and nothing else. No album art, no title, no
 * transport controls, no seek bar, no queue, no playback state — it takes no parameters
 * because it has nothing to render.
 *
 * Deliberate divergences from docs/player-rebuild-design.md, all scoped to this stage:
 * - The design doc's mechanism is SeekableTransitionState inside a SharedTransitionLayout
 *   (§1). This uses [AnchoredDraggableState] instead, because at two states with no shared
 *   elements and no content to reveal, the transition machinery would buy nothing while the
 *   old player's AnchoredDraggable drag/settle path is already proven on this exact gesture.
 *   Swapping the mechanism is its own later step.
 * - No coordination with SynthXBottomNav (§5). The nav does not slide away, and this sheet
 *   applies no bottom inset to clear it, so the collapsed bar currently overlays the nav
 *   instead of stacking above it. That is Stage 1.2's job and is intentionally left broken.
 * - No BackHandler, and no tap-to-collapse. Dragging down is currently the only way back to
 *   Collapsed.
 *
 * Every gesture constant here is copied verbatim from
 * [com.v1per.synthx.ui.player.PlayerSheetSkeleton] rather than re-tuned — the 0.4f
 * positional threshold, the 200dp velocity threshold, exponentialDecay's 1.8f friction
 * multiplier and [Motion.SpringStandard] as the snap spec.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerV2Host(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }
    // Higher friction lets a fling decelerate before handing off to the snap spring.
    val decayAnimationSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }

    // BoxWithConstraints, not fillMaxSize alone: the expanded height target has to be a real
    // measured number (maxHeight) rather than a guess, and the drag anchors need the same
    // number in pixels (constraints.maxHeight) to place the Collapsed anchor. Mirrors how
    // PlayerSheetSkeleton derives both.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()

        val draggableState = remember {
            AnchoredDraggableState(
                initialValue = PlayerV2State.Collapsed,
                positionalThreshold = { totalDistance -> totalDistance * 0.4f },
                velocityThreshold = { with(density) { 200.dp.toPx() } },
                snapAnimationSpec = Motion.SpringStandard,
                decayAnimationSpec = decayAnimationSpec,
            )
        }

        // Offset is measured from the top of this box downward, so Expanded (the sheet's top
        // edge at the very top) is 0f and Collapsed is everything-but-the-mini-bar.
        LaunchedEffect(availableHeightPx) {
            if (availableHeightPx > 0f) {
                draggableState.updateAnchors(
                    DraggableAnchors {
                        PlayerV2State.Collapsed at availableHeightPx - collapsedHeightPx
                        PlayerV2State.Expanded at 0f
                    },
                )
            }
        }

        // 0f = fully collapsed, 1f = fully expanded. Guards the pre-initialization NaN
        // AnchoredDraggableState.offset reports before the first updateAnchors() call lands.
        //
        // Read as a State in composition, which recomposes this subtree once per frame while
        // a drag is in flight. That is what the old player does too; the design doc's fix
        // (§5 — lambda-based modifiers reading the transition in the draw phase) belongs with
        // the SeekableTransitionState rewrite, not here, and there is no content below to
        // make the recompositions expensive.
        val progress by remember(availableHeightPx, collapsedHeightPx) {
            derivedStateOf {
                val offset = draggableState.offset
                if (offset.isNaN()) return@derivedStateOf 0f
                val collapsedAnchor = availableHeightPx - collapsedHeightPx
                if (collapsedAnchor <= 0f) 0f
                else ((collapsedAnchor - offset) / collapsedAnchor).coerceIn(0f, 1f)
            }
        }

        // The three animated properties, all driven off the one progress term so they cannot
        // drift out of step with each other or with the finger.
        val sheetHeight = lerpDp(CollapsedPlayerHeight, maxHeight, progress)
        // 24dp on the top corners only, deliberately breaking the app's 2dp shapes.small
        // rule — the documented Apple-Music-style exception, re-confirmed in the design doc
        // (§8) rather than accidentally normalized.
        val cornerRadius = lerpDp(0.dp, 24.dp, progress)
        // Lerped rather than flat: with no content whatsoever, this shift is the only way to
        // read progress mid-drag at a glance, and it costs one line and one import.
        val surfaceColor = lerp(Surface, SurfaceVariant, progress)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                .background(surfaceColor)
                // One unified detector arbitrating tap vs. drag itself, rather than
                // Modifier.anchoredDraggable paired with a separate tap modifier. Two
                // co-located detectors fight over pointer consumption — anchoredDraggable's
                // drag-start detector aborts permanently the moment it sees a consumed
                // pointer change, and a tap detector consumes the down as its first act.
                // PlayerSheetSkeleton hit exactly that and landed here; starting from its
                // conclusion rather than rediscovering it.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        // The same slop wait Foundation's own draggable/anchoredDraggable use
                        // internally: it returns null if the pointer lifted before crossing
                        // slop (a tap) and the first post-slop change otherwise (a drag).
                        var overSlopDelta = 0f
                        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            change.consume()
                            overSlopDelta = overSlop
                        }

                        if (drag == null) {
                            // Tap. Expands from Collapsed only; tapping while Expanded is a
                            // deliberate no-op, per this stage's scope.
                            //
                            // PlayerSheetSkeleton additionally guards this on down.isConsumed
                            // so a tap on a child button (mini play/pause) doesn't also expand
                            // the sheet. Omitted here because the sheet has no children at all
                            // to consume anything — it must come back the moment mini content
                            // does.
                            if (draggableState.currentValue == PlayerV2State.Collapsed) {
                                scope.launch { draggableState.animateTo(PlayerV2State.Expanded) }
                            }
                        } else {
                            // Drag. Feed the post-slop offset and then every subsequent delta
                            // straight into the state, exactly as anchoredDraggable's own
                            // internal drag loop would — this is what makes the morph track
                            // the finger continuously instead of snapping at the end.
                            velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                            draggableState.dispatchRawDelta(overSlopDelta)
                            verticalDrag(drag.id) { change ->
                                draggableState.dispatchRawDelta(change.positionChange().y)
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                            // Release. settle() applies the positional threshold (40% of the
                            // travel) and the 200dp/s velocity threshold configured above,
                            // seeded with the real release velocity, so a short flick still
                            // completes and a slow release falls back to nearest-state.
                            val velocity = velocityTracker.calculateVelocity().y
                            scope.launch { draggableState.settle(velocity) }
                        }
                    }
                },
        )
    }
}
