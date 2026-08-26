package com.v1per.synthx.ui.player.v2

import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import com.v1per.synthx.ui.theme.Motion
import kotlin.math.abs

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
 * What the gesture detector tells the transition, added by migration Step 8
 * (docs/player-v2-migration-plan.md). The only traffic between the pointer handler and the single
 * coroutine that owns [SeekableTransitionState] — see `gestureEvents` in [PlayerV2Host].
 *
 * Two events, not one per gesture kind: a tap and a drag-release both mean "animate to this state"
 * and both send [Settle]. That is the whole of Step 8's tap/release unification — before it, a tap
 * ran AnchoredDraggable's pixel-space spring while a release ran the transition's fraction-space
 * one, and the two could not be made to agree while both mechanisms existed.
 */
sealed interface PlayerV2GestureEvent {
    /**
     * The live finger position as **expansion** — 0f fully collapsed, 1f fully expanded,
     * pre-clamped by the producer.
     *
     * Deliberately not a fraction and deliberately direction-free. The transition's own `fraction`
     * means "how far from `currentState` toward `targetState`", so it inverts depending on which
     * way the sheet is pointed; keeping that inversion out of the drag loop and doing it once in
     * the consumer is what the plan means by "accumulate direction-free expansion in the drag loop
     * and convert in the consumer", and it is why the drag loop needs to know nothing about
     * [PlayerV2State] at all.
     */
    data class Seek(val expansion: Float) : PlayerV2GestureEvent

    /** Animate to a settled state and commit to it: a drag release, or a tap-to-expand. */
    data class Settle(val target: PlayerV2State) : PlayerV2GestureEvent
}

/**
 * The single duration every child animation of [PlayerV2Host]'s transition must share, added by
 * migration Step 3 (docs/player-v2-migration-plan.md).
 *
 * **Why it has to be exactly one number.** `SeekableTransitionState.seekTo(fraction)` positions
 * the whole transition by setting play time to `fraction × totalDurationNanos`, and
 * `totalDurationNanos` is the **maximum** over the transition's child animations. While every
 * child shares one duration, that maximum equals each child's own duration and the mapping from
 * `fraction` to any child's value stays 1:1. Give one child a longer spec and the max becomes
 * that child's duration, every *other* child finishes early, and the `expansion ↔ fraction`
 * identity this migration is built on quietly stops holding — with no error anywhere. So this
 * constant is used for the `expansion` tween below *and*, when Steps 5 and 6 land, for the
 * `fadeOut(targetAlpha = 1f)` hold specs on the expanded and mini content.
 *
 * **Why [Motion.DurationNormal] rather than a fresh number.** The design system already owns the
 * app's duration scale, and this is squarely the "normal" bucket; inventing a fifth value here
 * would be exactly the independently-written-constant drift that
 * [ExpandedContentHorizontalInset]'s doc describes cleaning up elsewhere in this file. It gets a
 * name of its own anyway rather than being spelled `Motion.DurationNormal` at each use site,
 * because the *thing being asserted* at those sites is "these all agree with each other", not
 * "these are all the normal duration" — a later decision to retune the player specifically then
 * has one place to land.
 *
 * **Still governs no visible motion.** As of Step 4 every consumer reads `expansion`, so this
 * tween is now load-bearing for *correctness* — its linearity is what keeps `expansion` equal to
 * `fraction` and therefore equal to the finger. Its **duration** still governs nothing, because
 * during a drag the transition is positioned by `seekTo` rather than run on a clock, and the
 * release is still AnchoredDraggable's spring mirrored in. Duration first becomes visible at
 * Step 7, where release switches to `SeekableTransitionState.animateTo` — that is the point to
 * re-examine whether 300ms is the right feel, not now.
 */
const val PlayerV2TransitionMs: Int = Motion.DurationNormal

/**
 * Stage 2.4b-2 — places [y] the same way `Modifier.offset(y = y)` would, except the placement
 * happens inside [androidx.compose.ui.layout.Placeable.PlacementScope.withMotionFrameOfReferencePlacement],
 * which tags this node's `NodeCoordinator` so that `LayoutCoordinates.localPositionOf(...,
 * includeMotionFrameOfReference = false)` skips *this node's own offset* when computing an
 * ancestor-to-descendant position. That flag is read per-`NodeCoordinator`
 * (`NodeCoordinator.toParentPosition`/`fromParentPosition`, `ui-android:1.12.0`), so it must be
 * applied at the exact node that carries the live offset — a descendant with no offset of its own
 * cannot retroactively exclude an ancestor's.
 *
 * `SharedElement.onLookaheadPlaced` computes exactly this excluded-offset position as
 * `structuralOffset` and only re-targets the shared-element bounds animation when
 * `structuralOffset` changes (`SharedTransitionStateMachine.updateTargetData`,
 * `animation-android:1.12.0`). A live per-frame offset like `bottomInset` feeding a plain
 * `Modifier.offset` therefore re-targets the bounds animation every frame during a drag — the
 * `totalDurationNanos` runaway Stage 2.4b-0's probe found. Foundation's own scroll containers
 * (`Scroll.kt`, `LazyListMeasure.kt`) use this exact pattern for the same reason: `scroll`/`drag`
 * offsets are motion, not structure, and `TargetData`'s own doc calls this out directly —
 * "Structural offset is intended to only track offsets from structural changes, as opposed to
 * scrolling/dragging offset changes."
 */
fun Modifier.motionFrameOffsetY(y: Dp): Modifier = layout { measurable, constraints ->
    val yPx = y.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        withMotionFrameOfReferencePlacement { placeable.placeRelative(0, yPx) }
    }
}

/**
 * Migration Step 2a — a verbatim port of Foundation's own private
 * `DraggableAnchors<T>.computeTarget` (AnchoredDraggable.kt:1483-1513 in
 * `foundation-android-1.12.0-sources.jar`), the target-selection half of the deprecated
 * `AnchoredDraggableState.settle(velocity)` this file no longer calls.
 *
 * It exists so the threshold logic survives the eventual removal of AnchoredDraggableState.
 * `SeekableTransitionState` has no settle at all — a release under it is just "pick a target,
 * then `animateTo` it" — so this function is the piece that has to be brought across by hand.
 * Porting it now, while the old mechanism is still in place and can be A/B'd against, is the
 * point of Step 2a: any difference in feel at this stage is a porting bug, isolated from every
 * later change.
 *
 * Faithful to the original in all three branches, in order:
 *
 * 1. **Not moving** (`velocity == 0f` exactly) — nearest anchor by absolute distance, direction
 *    and positional threshold not consulted at all.
 * 2. **Past the velocity threshold** (`abs(velocity) >= abs(velocityThreshold())`, i.e. 200dp/s
 *    here) — the next anchor in the direction of travel, positional threshold ignored. This is
 *    what lets a short flick complete a drag it never positionally earned.
 * 3. **Otherwise, positional** — and this is the branch that is easy to get wrong. The distance
 *    is measured from the anchor *behind the direction of motion* (`leftAnchorPosition` when
 *    moving forward, `rightAnchorPosition` when moving back), not from the nearest anchor. That
 *    asymmetry is the whole source of the release hysteresis: with this file's anchor geometry
 *    (offset measured downward, so Expanded = 0f and Collapsed = the larger offset), releasing
 *    while still moving *down* keeps the sheet expanded until progress has fallen below 0.6,
 *    while releasing while moving *up* commits to expanding as soon as progress reaches 0.4. A
 *    simplified "is progress past 0.4" two-branch version would collapse both cases onto the
 *    same 0.4 line and lose that.
 *
 * Two things in the original are deliberately *not* reproduced, both unreachable rather than
 * dropped:
 * - `settle()`'s `confirmValueChange(target)` veto and its rollback-to-`previousValue` branch
 *   (AnchoredDraggable.kt:1077-1082). [PlayerV2Host] constructs its state without a
 *   `confirmValueChange`, so it is the default `{ true }` and the veto branch is dead code. The
 *   callback is `internal` and could not be invoked from here in any case.
 * - `settle()`'s `requirePrecondition(usePreModifierChangeBehavior)` guard, which only checks
 *   that the deprecated threshold-carrying constructor was used — it was, unconditionally, a
 *   few lines above.
 *
 * The `!!`s are the original's, kept rather than softened to `?:` fallbacks: they can only fire
 * on an empty anchor set, and an empty anchor set would already have thrown out of
 * `dispatchRawDelta`'s `requireOffset()` on the drag's very first frame, long before any
 * release. A fallback here would be unreachable code that quietly changes what "faithful port"
 * means.
 *
 * **Migration Step 8 changed this function's callers, not this function.** It is still the
 * byte-for-byte port Step 2a made. What went away underneath it is where its two positional
 * inputs come from: the receiver was `draggableState.anchors` and is now [PlayerV2Host]'s
 * remembered `releaseAnchors` — the same `DraggableAnchors` value, just no longer owned by a
 * state — and `currentOffset` was `draggableState.requireOffset()` and is now the drag loop's
 * own expansion accumulator mapped back onto the same axis by
 * `collapsedAnchorPx * (1f - expansion)`. That mapping is the exact inverse of the one the drag
 * loop applies per delta, so the number reaching this function is the number `requireOffset()`
 * would have reported, and every branch below still sees the geometry it was written for.
 *
 * @param currentOffset the live drag position on the anchor axis, in px, measured downward from
 *   the top of the host — 0f at Expanded, `collapsedAnchorPx` at Collapsed.
 * @param velocity the release velocity in px/s, signed — positive is downward in this
 *   coordinate space. Still the real [VelocityTracker] reading, in the same units as
 *   [velocityThreshold], which is why the whole computation stays in pixel space.
 * @param positionalThreshold the 0.4-of-travel lambda from [PlayerV2Host].
 * @param velocityThreshold the 200dp/s lambda, likewise.
 */
fun <T> DraggableAnchors<T>.computeReleaseTarget(
    currentOffset: Float,
    velocity: Float,
    positionalThreshold: (totalDistance: Float) -> Float,
    velocityThreshold: () -> Float,
): T {
    require(!currentOffset.isNaN()) {
        "The offset provided to computeReleaseTarget must not be NaN."
    }
    val isMoving = abs(velocity) > 0.0f
    val isMovingForward = isMoving && velocity > 0f
    // When we're not moving, pick the closest anchor and don't consider directionality
    return if (!isMoving) {
        closestAnchor(currentOffset)!!
    } else if (abs(velocity) >= abs(velocityThreshold())) {
        closestAnchor(currentOffset, searchUpwards = isMovingForward)!!
    } else {
        val left = closestAnchor(currentOffset, false)!!
        val leftAnchorPosition = positionOf(left)
        val right = closestAnchor(currentOffset, true)!!
        val rightAnchorPosition = positionOf(right)
        val distance = abs(leftAnchorPosition - rightAnchorPosition)
        val relativeThreshold = abs(positionalThreshold(distance))
        val closestAnchorFromStart =
            if (isMovingForward) leftAnchorPosition else rightAnchorPosition
        val relativePosition = abs(closestAnchorFromStart - currentOffset)
        when (relativePosition >= relativeThreshold) {
            true -> if (isMovingForward) right else left
            false -> if (isMovingForward) left else right
        }
    }
}

/**
 * The one unified tap-vs-drag detector [PlayerV2Host] attaches to its sheet `Box`, moved out of
 * that composable's body verbatim to bring it under the project's 500-line limit — no behavior
 * change from the un-split version.
 *
 * **Keyed on `state.collapsedAnchorPx` — the current value read through [state] — not on [state]
 * itself.** `state` is a `@Stable` object whose identity never changes for the life of the host
 * (see [PlayerV2HostState]'s doc), so keying `pointerInput` on it would mean this detector never
 * restarts, even across a rotation or window resize that changes the sheet's travel. Keying on the
 * value it wraps is what makes the detector pick up the new geometry — this is a deliberate Step 8
 * fix and the single highest-risk line in this extraction.
 */
fun Modifier.playerV2DragGestures(state: PlayerV2HostState): Modifier =
    pointerInput(state.collapsedAnchorPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Nothing can be computed against a zero-width travel — unreachable in practice
            // (this box fills the screen) but the alternative to one line here is a crash.
            if (state.collapsedAnchorPx <= 0f) return@awaitEachGesture
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            // Same slop wait Foundation's own draggable/anchoredDraggable use internally: null if
            // the pointer lifted before crossing slop (a tap), the first post-slop change otherwise.
            var overSlopDelta = 0f
            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                change.consume()
                overSlopDelta = overSlop
            }

            if (drag == null) {
                // Tap. Expands from Collapsed only; tapping while Expanded is a deliberate no-op.
                // No `down.isConsumed` guard — still deliberately deferred, unrelated to this split.
                if (state.seekableState.currentState == PlayerV2State.Collapsed) {
                    state.gestureEvents.trySend(
                        PlayerV2GestureEvent.Settle(PlayerV2State.Expanded),
                    )
                }
            } else {
                // Drag. Accumulate *expansion* — direction-free, 0..1 — and send it. The
                // conversion to a transition fraction lives in the consumer; this loop knows
                // nothing about Collapsed-vs-Expanded or which way the transition is pointed.
                //
                // Accumulated from `expansionState.value` at drag start rather than re-read on
                // each delta, since `expansionState` is the *rendered* value and lags the finger
                // by a frame.
                //
                // Sign: `positionChange().y` is positive downward; expansion moves against it —
                // dragging up (negative y) expands.
                velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                var dragExpansion =
                    (state.expansionState.value - overSlopDelta / state.collapsedAnchorPx)
                        .coerceIn(0f, 1f)
                state.gestureEvents.trySend(PlayerV2GestureEvent.Seek(dragExpansion))
                verticalDrag(drag.id) { change ->
                    dragExpansion =
                        (dragExpansion - change.positionChange().y / state.collapsedAnchorPx)
                            .coerceIn(0f, 1f)
                    state.gestureEvents.trySend(
                        PlayerV2GestureEvent.Seek(dragExpansion),
                    )
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                }

                // Release: pick a target, then animate to it. `currentOffset` is the accumulator
                // converted back onto the anchor axis — the exact inverse of the per-delta
                // normalization above, so [computeReleaseTarget] sees the geometry it was
                // written for.
                val velocity = velocityTracker.calculateVelocity().y
                val releaseTarget = state.releaseAnchors.computeReleaseTarget(
                    currentOffset = state.collapsedAnchorPx * (1f - dragExpansion),
                    velocity = velocity,
                    positionalThreshold = state.positionalThreshold,
                    velocityThreshold = state.velocityThreshold,
                )
                state.gestureEvents.trySend(
                    PlayerV2GestureEvent.Settle(releaseTarget),
                )
            }
        }
    }
