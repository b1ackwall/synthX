package com.v1per.synthx.ui.player.v2

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * [PlayerV2Host]'s mechanism, bundled: the [SeekableTransitionState] and the [Transition] built
 * over it, the gesture [Channel] that is the only path into either, the release-target inputs
 * (`releaseAnchors`, the two threshold lambdas), and the geometry (`collapsedAnchorPx`) both the
 * gesture detector and the release spring are scaled by.
 *
 * Split out of [PlayerV2Host]'s body verbatim (no behavior change) to bring that file under the
 * project's 500-line limit — see [rememberPlayerV2HostState] for the exact remember/key
 * preservation this depends on. Matches this codebase's existing state-holder convention (see
 * `QueueModeState`, `RefinedBottomSheetState`): a `@Stable` class with an `internal constructor`,
 * built by a `remember...State()` factory, not intended to be constructed directly.
 *
 * **`collapsedAnchorPx`, `releaseAnchors`, and `releaseSpring` are `var`s, not `val`s, on
 * purpose.** This object's identity is created once (via a bare `remember`) and never changes for
 * the life of the host, but the geometry it wraps *does* change — on rotation or a window resize.
 * [rememberPlayerV2HostState] re-derives all three every recomposition and assigns them onto this
 * same instance, exactly mirroring what the un-split code did with plain `remember(collapsedAnchorPx)`
 * locals. The gesture modifier below reads `state.collapsedAnchorPx` — the current *value* — as its
 * `pointerInput` key; if it instead keyed on `state` itself, the detector would never restart on a
 * resize, because this object's identity is stable by design. That restart-on-resize behavior is a
 * deliberate Step 8 fix and must not regress.
 */
@Stable
class PlayerV2HostState internal constructor(
    val seekableState: SeekableTransitionState<PlayerV2State>,
    val transition: Transition<PlayerV2State>,
    val expansionState: State<Float>,
    val gestureEvents: Channel<PlayerV2GestureEvent>,
    val positionalThreshold: (totalDistance: Float) -> Float,
    val velocityThreshold: () -> Float,
    collapsedAnchorPx: Float,
    releaseAnchors: DraggableAnchors<PlayerV2State>,
    releaseSpring: FiniteAnimationSpec<Float>,
) {
    /** Live delegated read — same shape as the old `val expansion by expansionState` unwrap. */
    val expansion: Float by expansionState

    var collapsedAnchorPx: Float = collapsedAnchorPx
        internal set

    var releaseAnchors: DraggableAnchors<PlayerV2State> = releaseAnchors
        internal set

    var releaseSpring: FiniteAnimationSpec<Float> = releaseSpring
        internal set
}

/**
 * Builds (once) and refreshes (every recomposition) a [PlayerV2HostState] for [PlayerV2Host].
 *
 * A genuine `@Composable` function, not `inline` — `remember`/`LaunchedEffect` calls inside it
 * allocate against this call's own position in the slot table, which is the same position
 * [PlayerV2Host] used to call them from directly. That is what makes this extraction behaviorally
 * identical to the un-split code rather than merely similar to it.
 *
 * Every `remember` key below is the exact key the un-split code used — none simplified away:
 * `positionalThreshold`/`velocityThreshold` stay keyless (closing over the first `density` seen,
 * deliberately not re-keyed on it — a pre-existing, unrelated-to-this-refactor choice), `seekableState`
 * and `gestureEvents` stay keyless (created once for the host's whole life), and `releaseAnchors`/
 * `releaseSpring` stay keyed on `collapsedAnchorPx` (recomputed whenever the measured height changes).
 */
@Composable
fun rememberPlayerV2HostState(
    availableHeightPx: Float,
    density: Density,
): PlayerV2HostState {
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }
    val collapsedAnchorPx = availableHeightPx - collapsedHeightPx

    val positionalThreshold: (totalDistance: Float) -> Float =
        remember { { totalDistance -> totalDistance * 0.4f } }
    val velocityThreshold: () -> Float =
        remember { { with(density) { 200.dp.toPx() } } }

    val releaseAnchors = remember(collapsedAnchorPx) {
        DraggableAnchors {
            PlayerV2State.Collapsed at collapsedAnchorPx
            PlayerV2State.Expanded at 0f
        }
    }

    val seekableState = remember { SeekableTransitionState(PlayerV2State.Collapsed) }
    val transition = rememberTransition(seekableState, label = "PlayerV2Expansion")
    val expansionState = transition.animateFloat(
        transitionSpec = { tween(PlayerV2TransitionMs, easing = LinearEasing) },
        label = "expansion",
    ) { state -> if (state == PlayerV2State.Expanded) 1f else 0f }

    val releaseSpring = remember(collapsedAnchorPx) {
        spring(
            dampingRatio = Motion.SpringStandard.dampingRatio,
            stiffness = Motion.SpringStandard.stiffness,
            visibilityThreshold = if (collapsedAnchorPx > 0f) {
                Spring.DefaultDisplacementThreshold / collapsedAnchorPx
            } else {
                // Anchors not measured yet; no release can be in flight. Any value is
                // unreachable — use the framework default rather than divide by zero.
                Spring.DefaultDisplacementThreshold
            },
        )
    }

    val gestureEvents = remember { Channel<PlayerV2GestureEvent>(Channel.CONFLATED) }

    val state = remember(seekableState, transition, expansionState, gestureEvents) {
        PlayerV2HostState(
            seekableState = seekableState,
            transition = transition,
            expansionState = expansionState,
            gestureEvents = gestureEvents,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            collapsedAnchorPx = collapsedAnchorPx,
            releaseAnchors = releaseAnchors,
            releaseSpring = releaseSpring,
        )
    }
    // Refresh the geometry on the stable instance every recomposition — see the class doc for
    // why these are `var`s and why this must not become a re-key of the `remember` above.
    state.collapsedAnchorPx = collapsedAnchorPx
    state.releaseAnchors = releaseAnchors
    state.releaseSpring = releaseSpring

    // Same key set as the un-split code: seekableState/gestureEvents never change, so in practice
    // this only restarts when releaseSpring does (i.e. when collapsedAnchorPx changes).
    LaunchedEffect(seekableState, gestureEvents, releaseSpring) {
        consumeGestureEvents(gestureEvents, seekableState, releaseSpring)
    }

    return state
}

/**
 * The consumer loop moved out of [rememberPlayerV2HostState]'s `LaunchedEffect` verbatim. An
 * extension on [CoroutineScope] rather than a plain `suspend fun`, so the `launch` calls inside
 * resolve to — and are cancelled together with — the exact same scope the un-split
 * `LaunchedEffect` block provided them.
 *
 * The single owner of every mutation of [seekableState]. See [PlayerV2GestureEvent] for the two
 * event kinds and [PlayerV2Host]'s (now [rememberPlayerV2HostState]'s) `gestureEvents` doc for why
 * a channel with exactly one consumer is what makes `MutatorMutex` interruption unreachable here.
 */
private suspend fun CoroutineScope.consumeGestureEvents(
    events: ReceiveChannel<PlayerV2GestureEvent>,
    seekableState: SeekableTransitionState<PlayerV2State>,
    releaseSpring: FiniteAnimationSpec<Float>,
) {
    // The settle in flight, if any. Never read for anything but cancellation.
    var settle: Job? = null
    for (event in events) {
        // Unconditional, and `cancelAndJoin` rather than `cancel`: joining is what guarantees
        // `seekTo` and `animateTo` never overlap. A no-op against an already-finished settle.
        settle?.cancelAndJoin()
        settle = null
        when (event) {
            is PlayerV2GestureEvent.Seek -> {
                // `seekTo` hard-throws on an out-of-range fraction; the producer already clamps
                // its accumulator, this is the second of two belts.
                val expansionTarget = event.expansion.coerceIn(0f, 1f)

                // `seekTo` is a silent no-op when `currentState == targetState` and no new target
                // is named. Naming the state opposite to `currentState` makes that unreachable —
                // and `currentState` never moves during a drag (only `snapTo`/`animateTo` write
                // it), so every seek of one gesture names the same pair.
                val target =
                    if (seekableState.currentState == PlayerV2State.Collapsed) {
                        PlayerV2State.Expanded
                    } else {
                        PlayerV2State.Collapsed
                    }

                // `fraction` means "how far from currentState toward target" — expansion itself
                // when expanding, its complement when collapsing. Both land expansion == finger.
                val fraction =
                    if (target == PlayerV2State.Expanded) {
                        expansionTarget
                    } else {
                        1f - expansionTarget
                    }
                seekableState.seekTo(fraction.coerceIn(0f, 1f), target)
            }

            is PlayerV2GestureEvent.Settle -> {
                // The release, and the tap too — one mechanism, one spring, for both.
                // No velocity argument (no overload takes one) and nothing is lost: the release
                // is velocity-free by design, and `animateTo` seeds from `ZeroVelocity` since we
                // were seeking, not animating. Velocity decides *which* state, via
                // [computeReleaseTarget], not the curve.
                //
                // No `snapTo` afterwards: `animateTo` commits `currentState = targetState` itself
                // on completion, which is what keeps the opposite-state rule above correct for
                // the next drag.
                //
                // Launched rather than awaited so the loop goes straight back to receiving and
                // can interrupt this — a `CancellationException` inside a `launch`ed child is
                // ordinary cancellation, not a failure.
                settle = launch { seekableState.animateTo(event.target, releaseSpring) }
            }
        }
    }
}
