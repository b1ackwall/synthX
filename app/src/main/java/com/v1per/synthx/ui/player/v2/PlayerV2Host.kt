package com.v1per.synthx.ui.player.v2

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.DragResistantIconButton
import com.v1per.synthx.ui.components.MoreOptionsButton
import com.v1per.synthx.ui.components.PlayerAlbumArt
import com.v1per.synthx.ui.components.PlayerSeekBar
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
private sealed interface PlayerV2GestureEvent {
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
private const val PlayerV2TransitionMs: Int = Motion.DurationNormal

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
private fun Modifier.motionFrameOffsetY(y: Dp): Modifier = layout { measurable, constraints ->
    val yPx = y.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        withMotionFrameOfReferencePlacement { placeable.placeRelative(0, yPx) }
    }
}

/**
 * Stage 1.1 of the player rebuild — see docs/player-rebuild-design.md for the eventual
 * target architecture.
 *
 * This was originally a blank dark surface that morphs between a 64dp mini bar and a
 * full-height sheet and nothing else. Stage 2.1 added real mini-bar content (art,
 * title/artist, play/pause) — see [PlayerV2MiniContent]. Stage 2.2 added real expanded
 * content — see [PlayerV2ExpandedContent] — statically laid out at fixed final positions, no
 * drag-progress-driven offsets. Stage 2.3 added the envelope reveal-clip on top of that
 * static layout — see [EnvelopeRevealShape] — so the content now reveals bottom-up with
 * progress instead of popping in/out at the drag's midpoint. Stage 2.3b replaced the 320dp
 * placeholder gap above the title with a real album art frame, measured rather than budgeted,
 * and made the expanded layout genuinely static by sizing its container at the sheet's
 * *fully expanded* height instead of letting it track the live sheetHeight — see
 * [PlayerV2ExpandedContent]. The art is still statically positioned: no shared-element morph
 * between the mini and expanded frames (Stage 2.4).
 *
 * **Migration complete as of Step 8.** This composable no longer diverges from
 * docs/player-rebuild-design.md §1 on the mechanism: the sheet is a `SeekableTransitionState`
 * inside a `SharedTransitionLayout`, drag deltas feed `seekTo` and releases feed `animateTo`,
 * and `AnchoredDraggableState` — along with its anchors, its decay spec, the shadow bridge that
 * mirrored it, and the deprecated constructor it required — is gone from this file entirely.
 * Only `DraggableAnchors` survives, as a plain immutable lookup table for
 * [computeReleaseTarget]; see `releaseAnchors` for why.
 *
 * Remaining deliberate divergences, all still scoped:
 * - No BackHandler, and no tap-to-collapse. Dragging down is currently the only way back to
 *   Collapsed.
 * - No `down.isConsumed` guard on the tap branch, so a tap on a child button also expands the
 *   sheet. Deferred out of Step 8 on purpose — see the tap branch for why, and for the fact
 *   that it is now a one-line follow-up with nothing blocking it.
 * - The album art is still statically positioned; the shared-element morph is Stage 2.4b, which
 *   the [SharedTransitionScope] receiver and both `AnimatedVisibilityScope`s now exist to make
 *   a pure addition.
 *
 * Stage 1.2 has since added the nav coordination that this list previously called out as
 * missing (§5): [onProgressChanged] reports the live drag progress outward, and the sheet
 * applies the matching bottom inset so the collapsed bar stacks above the nav rather than
 * overlaying it. Everything else above still stands.
 *
 * Every gesture constant here is copied verbatim from
 * [com.v1per.synthx.ui.player.PlayerSheetSkeleton] rather than re-tuned — the 0.4f positional
 * threshold, the 200dp velocity threshold and [Motion.SpringStandard] as the settle spec. Two
 * of those have since been re-expressed rather than re-tuned: the decay spec's 1.8f friction
 * multiplier went away at Step 2b with the velocity-seeded release it fed, and
 * [Motion.SpringStandard] is now applied through `releaseSpring`, which adds the
 * `visibilityThreshold` a 0..1 fraction needs and changes nothing else about it.
 *
 * @param song the currently playing song. Required, not nullable: the call site
 *   (BottomChromeOverlay) only composes this at all inside
 *   `AnimatedVisibility(visible = showBottomBar && currentSong != null)`, so a null song can
 *   never actually reach here — a nullable parameter would just add a dead branch.
 * @param isPlaying drives the mini bar's play/pause icon and [PlayerAlbumArt]'s breathing
 *   scale.
 * @param isBuffering when true, the mini bar's transport button shows a spinner instead of
 *   the play/pause icon — mirrors the old (detached) PlayerSkeletonMiniContent exactly.
 * @param onPlayPauseClick wired to both the mini bar's and the expanded transport's
 *   play/pause buttons.
 * @param currentPosition / @param duration / @param onSeek feed [PlayerSeekBar] in the
 *   expanded controls block.
 * @param hasPrevious / @param hasNext gate the expanded transport's prev/next buttons —
 *   dimmed via [Alpha.Disabled] when false, exactly like the old (detached)
 *   PlayerSkeletonControlsOverlay.
 * @param onPreviousClick / @param onNextClick wired to the expanded transport's prev/next
 *   buttons.
 * @param onProgressChanged called with the live 0..1 collapse/expand progress whenever it
 *   changes, so chrome outside this composable (the bottom nav) can move in lockstep. Fired
 *   from a [SideEffect] rather than from the composition body — see the call site below.
 *
 * @receiver [SharedTransitionScope] — threaded in by migration Step 1
 *   (docs/player-v2-migration-plan.md) and **deliberately unused for now**. Stage 2.4b consumes
 *   it to give the album art a `Modifier.sharedElement`, morphing it between the mini and
 *   expanded frames; taking the receiver now means 2.4b is a pure addition rather than a
 *   signature change rippling back out to [BottomChromeOverlay].
 *
 *   Declared as an extension receiver rather than a `context(SharedTransitionScope)` parameter:
 *   Kotlin's old context *receivers* were removed in 2.2, and their replacement, context
 *   *parameters*, is still behind `-Xcontext-parameters` at this project's Kotlin 2.4.10 with
 *   the flag not enabled — so `context(...)` would not compile here at all. Extension-receiver
 *   style is also what Compose itself uses for scoped composables, and what this file used to do
 *   for `BoxScope.PlayerV2MiniContent` before migration Step 6 took that receiver away.
 */
@Composable
fun SharedTransitionScope.PlayerV2Host(
    song: Song,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isBuffering: Boolean = false,
    onPlayPauseClick: () -> Unit = {},
    currentPosition: Long = 0L,
    duration: Long = 0L,
    onSeek: (Long) -> Unit = {},
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onPreviousClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onProgressChanged: (Float) -> Unit = {},
) {
    val density = LocalDensity.current
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }

    // BoxWithConstraints, not fillMaxSize alone: the expanded height target has to be a real
    // measured number (maxHeight) rather than a guess, and the drag anchors need the same
    // number in pixels (constraints.maxHeight) to place the Collapsed anchor. Mirrors how
    // PlayerSheetSkeleton derives both.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()

        // The full pixel distance between the two anchors — the sheet's entire travel, and as
        // of Step 8 the single number the whole mechanism is scaled by. [releaseAnchors] places
        // the Collapsed anchor at it, [releaseSpring]'s visibility threshold is 0.01px expressed
        // as a fraction of it, the drag loop divides every pointer delta by it to get expansion,
        // and the release multiplies back through it to hand [computeReleaseTarget] an offset.
        // Those last two are exact inverses of each other by construction, which is what lets
        // the drag work in 0..1 while the threshold logic stays in the pixel space it was tuned
        // in.
        val collapsedAnchorPx = availableHeightPx - collapsedHeightPx

        // The two release thresholds, still the values copied verbatim from
        // PlayerSheetSkeleton: 0.4 of the travel positionally, 200dp/s by velocity.
        //
        // They were hoisted at Step 2a so the release site and the AnchoredDraggableState
        // constructor could share one pair of lambda instances rather than each writing 0.4f and
        // 200.dp out again. Step 8 deleted the state, so there is nothing left to share them
        // *with* — [computeReleaseTarget] is now the only reader. They stay as named values
        // regardless, because that function's signature takes them as parameters (it is a
        // verbatim port of Foundation's own `computeTarget`, which does the same), and because a
        // threshold with a name is a threshold that can be found.
        //
        // remember with no keys, as before: the velocity lambda closes over `density`, and
        // re-keying it on `density` would be a behavior change rather than a fix — out of scope
        // here exactly as it was at Step 2a.
        val positionalThreshold: (totalDistance: Float) -> Float =
            remember { { totalDistance -> totalDistance * 0.4f } }
        val velocityThreshold: () -> Float =
            remember { { with(density) { 200.dp.toPx() } } }

        // The anchor geometry, surviving the removal of AnchoredDraggableState.
        //
        // This used to be handed to `draggableState.updateAnchors(...)` from a
        // `LaunchedEffect(availableHeightPx)`. Both are gone: there is no state to update, and
        // `remember` already re-derives this whenever the measured height changes, with no frame
        // of lag and no coroutine.
        //
        // **Why [DraggableAnchors] is kept at all** when the plan's Step 8 line says to delete
        // "the anchors". `DraggableAnchors` is not `AnchoredDraggableState`, and nothing about it
        // is deprecated or experimental — it is an immutable two-entry lookup table
        // (AnchoredDraggable.kt:549, plain public API) with no state, no coroutines and no mutex.
        // Everything that made the *state* worth deleting is absent here.
        //
        // Keeping it is what lets [computeReleaseTarget] stay the verbatim port Step 2a made it.
        // The alternative — re-expressing the algorithm in 0..1 expansion space — means
        // re-deriving `closestAnchor`'s exact edge semantics by hand, including the fact that its
        // `distance <= minDistance` comparison lets a *later* anchor win ties
        // (AnchoredDraggable.kt:1620-1646), which is what decides the zero-velocity release at
        // exactly 0.5. That is precisely the "subtly wrong, and the hysteresis is quietly
        // corrupted" failure this migration has spent eight steps avoiding. Offset space also
        // keeps the velocity threshold honest: 200dp/s is a gesture velocity in px/s, which is
        // what [VelocityTracker] reports and what `computeReleaseTarget` compares against —
        // converting it to expansion-per-second would buy nothing and add a conversion to get
        // wrong.
        //
        // Offset is measured from the top of this box downward, so Expanded (the sheet's top edge
        // at the very top) is 0f and Collapsed is everything-but-the-mini-bar.
        val releaseAnchors = remember(collapsedAnchorPx) {
            DraggableAnchors {
                PlayerV2State.Collapsed at collapsedAnchorPx
                PlayerV2State.Expanded at 0f
            }
        }

        // ── The transition: the whole mechanism ──────────────────────────────────────────
        //
        // Introduced by Step 3 as pure addition, consumed by nothing, and shadow-fed from an
        // AnchoredDraggableState. Step 4 flipped every consumer onto `expansionState`, Step 7
        // moved the release onto it, and Step 8 removed the shadow and the state behind it —
        // the drag now writes this directly. This is the design doc's §1 mechanism, arrived at.
        val seekableState = remember { SeekableTransitionState(PlayerV2State.Collapsed) }

        // rememberTransition is mandatory, not stylistic. A SeekableTransitionState is inert
        // on its own: rememberTransition is what installs its SnapshotStateObserver
        // (Transition.kt:941-954, `transitionState.snapshotStateObserver = ...`) and what runs
        // the observeTotalDuration() LaunchedEffect (:955-962) that seekTo suspends on via
        // waitForCompositionAfterTargetStateChange(). Constructing the state and calling
        // seekTo on it without this line would not fail loudly — it would just never move.
        //
        // Resolves to the plain overload at Transition.kt:922-926, not the
        // @ExperimentalDeferredTransitionApi one at :208, which takes a DeferredTransitionState
        // specifically. No opt-in needed.
        val transition = rememberTransition(seekableState, label = "PlayerV2Expansion")

        // The progress source the whole migration is built around.
        //
        // Keyed on *state*, not on fraction-plus-a-direction-mapping. This is Step 0's Finding
        // 2 taken literally: SeekableTransitionState.fraction resets to 0f on a target change
        // and currentState becomes the target that was never reached, so any `1f - fraction`
        // style mapping renders a half-open sheet as fully open for one frame. animateFloat
        // asks only "what is this value at Collapsed, and at Expanded" and lets the framework's
        // own initial-value-animation machinery carry continuity across reversals — the case
        // that mapping gets wrong is the case it handles for free.
        //
        // LinearEasing, deliberately: it is what makes `fraction` and this value the same
        // number rather than merely correlated. seekTo positions the transition by play time
        // (fraction × totalDurationNanos) and a linear tween of exactly that duration reads
        // back as lerp(from, to, fraction). Any eased curve would still be *correct*, but the
        // shadow below — which sets fraction from a finger position — would no longer track
        // the finger 1:1. See PlayerV2TransitionMs for why the duration must be shared.
        //
        // Held as a State<Float>, with a separate `by` unwrap below, because the consumers need
        // both shapes. EnvelopeRevealShape and the mini fade take the State object itself and
        // read `.value` in the draw phase; the lerps and the gate need a plain Float in the
        // composition phase. This is exactly the split progressState used to serve, and the
        // instance is safe to hand out as an identity key — animateValue's returned animation
        // is `remember(this)`-scoped to the transition (Transition.kt:2119-2120), so it is the
        // same object on every recomposition, which is what PlayerV2ExpandedContent's
        // `remember(progress) { EnvelopeRevealShape(progress) }` relies on.
        val expansionState = transition.animateFloat(
            transitionSpec = { tween(PlayerV2TransitionMs, easing = LinearEasing) },
            label = "expansion",
        ) { state -> if (state == PlayerV2State.Expanded) 1f else 0f }

        // Migration Step 4: the composition-phase half of the swap. Every `progress` read that
        // used to unwrap progressState now unwraps this instead — same reads, same formulas,
        // same once-per-frame recomposition of this subtree during a drag, different source.
        // The design doc's §5 fix (lambda-based modifiers so the lerps stop recomposing at all)
        // is deliberately still not attempted here; Step 4's success criterion is
        // pixel-identical behavior, and changing the read phase at the same time as the source
        // would put two variables in that comparison.
        val expansion by expansionState

        // ── Migration Step 7: the release animation moves to the transition ───────────────
        //
        // [Motion.SpringStandard] re-expressed for a 0..1 fraction. Same damping ratio, same
        // stiffness — read off the shared token rather than restated, so a later retune of the
        // app's standard spring still reaches this one — and one thing added: an explicit
        // `visibilityThreshold`.
        //
        // **Why the threshold is not optional.** `SeekableTransitionState.animateTo` applies the
        // spec to the transition's *fraction*: it builds an animation with `start[0] = fraction`
        // and `targetValue = Target1` (= 1f), then sets `animationSpecDuration =
        // spec.getDurationNanos(...)` and snaps the value to exactly 1f the moment play time
        // reaches that duration (Transition.kt:769-786, :512-535). So the spec's convergence
        // threshold is, quite literally, the point at which the sheet stops moving and jumps the
        // rest of the way.
        //
        // `Motion.SpringStandard` carries no threshold, so it falls through to
        // [Spring.DefaultDisplacementThreshold] = 0.01f (VectorizedAnimationSpec.kt:839).
        // Against the pixel-space offset AnchoredDraggableState animates today that is 0.01 of a
        // *pixel* — for practical purposes full convergence. Against a 0..1 fraction it is 1% of
        // the sheet's entire travel: on this device's ~$collapsedAnchorPx px that is ~20px of
        // remaining motion discarded in a single frame. Same spec, same damping, ~2000× looser
        // criterion. Numerically: with StiffnessMediumLow (ω = 20 rad/s) and critical damping,
        // the settle would end at ~0.33s instead of the ~0.75s it runs today — a ~400ms tail
        // simply absent. That is the plan's Step 7 risk #2, and it is not a subtle one.
        //
        // **The value.** The honest conversion is the proportional one: the same 0.01px, divided
        // by the travel it is now being measured against. That is not a tuning choice — it is the
        // identity that makes the fraction-space spring terminate on exactly the same *physical*
        // displacement as the pixel-space spring it replaces, which is what "behavior-identical"
        // has to mean here. It also self-adjusts across devices, where a hand-picked constant
        // would silently mean a different thing on a tablet than on a phone.
        //
        // A rounder number (1e-3, say ≈ 2px) was the alternative and is deliberately not taken:
        // it would be a *decision to change the feel*, made in the same step that is trying to
        // prove the mechanism swap changed nothing. If  finds the ~0.75s tail too long, that
        // is a real and separate question — and this is the one line to change to answer it.
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

        // The one pipe from the gesture detector to the coroutine that owns the transition.
        //
        // **Why a channel rather than direct calls.** Every mutating entry point on
        // SeekableTransitionState — `seekTo`, `snapTo`, `animateTo` — runs inside
        // `mutatorMutex.mutate { }` (Transition.kt:560, :609, :720), and an interrupted mutator
        // does not fail quietly. Reading `MutatorMutex` itself (InternalMutatorMutex.kt): `mutate`
        // opens a `coroutineScope`, registers *that scope's* Job as the current mutator, and a
        // later mutator of equal priority calls `job.cancel(MutationInterruptedException())` on
        // it; the interrupted scope then rethrows that `CancellationException` **out to its
        // caller**. When that caller is a long-lived `LaunchedEffect` whose own Job is perfectly
        // healthy, the exception propagates out of it, completes the coroutine exceptionally, and
        // — keys unchanged — it never restarts. Step 7 found this, and it is the reason this
        // architecture exists: a second coroutine touching `seekableState` does not cause a
        // glitch, it permanently kills whichever one it interrupts.
        //
        // A channel makes that unrepresentable rather than merely unlikely. The producer (the
        // pointer handler) only ever calls `trySend`, which touches no mutex and can neither
        // suspend nor fail on a conflated channel. The consumer is the single coroutine below,
        // and it is the only caller of `seekTo`/`animateTo` anywhere in this file. With exactly
        // one contender, `tryMutateOrCancel` never has an `oldMutator` to cancel and
        // `MutationInterruptedException` is unreachable.
        //
        // **Conflated**, per the plan. During a drag only the newest finger position matters, so
        // dropping superseded [PlayerV2GestureEvent.Seek]s is the intent rather than a
        // compromise. Two orderings are worth being explicit about, since conflation drops by
        // recency and not by kind: a `Settle` landing on top of an undrained `Seek` replaces it,
        // which is right (the finger has left); a `Seek` landing on top of an undrained `Settle`
        // also replaces it, which is also right (a new gesture has begun and will send its own
        // `Settle` when it ends) — in between, the transition simply stays mid-seek rather than
        // committing to a state nobody is asking for any more.
        val gestureEvents = remember { Channel<PlayerV2GestureEvent>(Channel.CONFLATED) }

        // The consumer. The single owner of every mutation of [seekableState], and — as of
        // Step 8 — the only thing driving the sheet at all.
        //
        // Through Step 7 this was a *shadow*: it watched an AnchoredDraggableState via
        // `snapshotFlow` and mirrored its offset in. There is nothing left to shadow. Deltas now
        // arrive here directly from the pointer handler, and the whole `progressState` /
        // `settledValue` / `snapTo`-on-settle bridge is gone with the state it existed to mirror.
        //
        // Two structural properties this shape buys, both of which were bought expensively at
        // earlier steps and are now free:
        //
        //  1. **One mutator.** See [gestureEvents]. Every `seekTo` and every `animateTo` in this
        //     file happens on this coroutine, in the order the gestures happened, so the
        //     MutatorMutex interruption that would permanently kill this `LaunchedEffect` has no
        //     second party to come from.
        //  2. **The release cannot be raced by a late seek.** Every event first cancels and
        //     *joins* any settle still running, so `seekTo` and `animateTo` never overlap even
        //     though the settle lives in its own child coroutine. The plan's "late seekTo
        //     cancels the release, sheet sticks halfway" failure is not handled here — it is
        //     unrepresentable, because the two can never be in flight at once.
        //
        // **Why the settle is a cancellable child rather than an inline await.** Running
        // `animateTo` inline — the obvious shape — means a `Seek` arriving mid-settle waits for
        // the settle to finish and is *then* applied. That is not merely unresponsive, it is
        // wrong: the drag loop bases its accumulator on the expansion it read at finger-down, so
        // a Seek computed from 0.8 gets applied after the settle has already carried the sheet
        // to 1.0, and the sheet visibly jumps backwards the moment the spring ends. Cancelling
        // the settle instead means the sheet is grabbed exactly where the finger found it, which
        // is also what the pre-migration player did — `dispatchRawDelta` took no mutex and wrote
        // the offset straight through a running settle.
        //
        // Cancellation is safe on both sides. `MutatorMutex.mutate` releases in a `finally`, so
        // `cancelAndJoin` leaves the mutex free and `currentMutator` null before the loop
        // touches `seekableState` again. And a half-finished `animateTo` is cleanly taken over
        // by the next seek in both directions, because `seekTo`'s same-target branch clears
        // `currentAnimation` outright (Transition.kt:613-618): interrupt an expand and
        // `currentState` is still Collapsed, so the next seek names Expanded, `fraction = e` and
        // `expansion = lerp(0f, 1f, e) = e`; interrupt a reversal-collapse and `currentState` is
        // Expanded (set at :728), so the next seek names Collapsed, `fraction = 1f - e` and
        // `expansion = lerp(1f, 0f, 1f - e) = e`. Either way the sheet stays exactly under the
        // finger.
        //
        // Keyed on [releaseSpring] as well as the state and the channel: the spring is derived
        // from the measured travel, and without the key a size change would leave this coroutine
        // holding the spec it captured at first composition. Restarting drops any in-flight
        // animation, which on a size change is the right thing anyway.
        LaunchedEffect(seekableState, gestureEvents, releaseSpring) {
            // The settle in flight, if any. Never read for anything but cancellation — the
            // loop's only question is "is something animating that I must stop first".
            var settle: Job? = null
            for (event in gestureEvents) {
                // Unconditional, and `cancelAndJoin` rather than `cancel`: joining is what makes
                // the no-overlap guarantee above real, since `cancel` alone would return before
                // the child had unwound out of `mutate` and released the mutex. A no-op against
                // a settle that already finished on its own.
                settle?.cancelAndJoin()
                settle = null
                when (event) {
                    is PlayerV2GestureEvent.Seek -> {
                        // Finding 4, gotcha 1 — and this is the first step where it is a real
                        // call site rather than a mirror. `seekTo` hard-throws on an
                        // out-of-range fraction (`requirePrecondition(fraction in 0f..1f)`,
                        // Transition.kt:604-606) instead of coercing. The producer already
                        // clamps its accumulator, so this is the second of two belts.
                        //
                        // It is explicitly *not* a NaN guard: `Float.coerceIn` compares with
                        // `<` and `>`, both of which are false against NaN, so a NaN passes
                        // straight through and would fail the same precondition. NaN is instead
                        // made unreachable at the source — the drag loop returns early rather
                        // than divide by a zero travel, and `expansionState` is a lerp between
                        // 0f and 1f and cannot produce one.
                        val expansionTarget = event.expansion.coerceIn(0f, 1f)

                        // Finding 4, gotcha 2. `seekTo` returns silently without doing anything
                        // when `currentState == targetState` and no new target is named
                        // (Transition.kt:613-618) — from a settled Collapsed, `seekTo(0.2f)`
                        // moves nothing, with no exception and no log. Naming the state opposite
                        // to `currentState` makes that unreachable: the opposite of a value is
                        // never equal to it, so the first seek of every drag always names a real
                        // target.
                        //
                        // It is also stable for the whole drag, which is what makes this correct
                        // rather than merely non-crashing. `seekTo` never writes `currentState`
                        // (verified: the only assignments in SeekableTransitionState are in
                        // `snapTo` :574 and `animateTo` :728, :791), so `currentState` stays at
                        // the state the drag *started* from for the drag's entire length. Every
                        // seek of one gesture therefore names the same pair, and a mid-drag
                        // reversal is just a smaller fraction against that same pair — no
                        // direction tracking, no re-targeting, nothing to get wrong.
                        val target =
                            if (seekableState.currentState == PlayerV2State.Collapsed) {
                                PlayerV2State.Expanded
                            } else {
                                PlayerV2State.Collapsed
                            }

                        // `fraction` is "how far from currentState toward target", so it is
                        // expansion itself when expanding and its complement when collapsing.
                        // Both directions land expansion == the finger: lerp(0f, 1f, e) = e, and
                        // lerp(1f, 0f, 1f - e) = e. This is the one place the inversion lives,
                        // exactly as the plan asks — the drag loop above accumulates
                        // direction-free expansion and knows nothing about it.
                        val fraction =
                            if (target == PlayerV2State.Expanded) {
                                expansionTarget
                            } else {
                                1f - expansionTarget
                            }
                        seekableState.seekTo(fraction.coerceIn(0f, 1f), target)
                    }

                    is PlayerV2GestureEvent.Settle -> {
                        // The release, and — as of Step 8 — the tap too. Step 7 left
                        // tap-to-expand on `draggableState.animateTo`, which meant a tap and a
                        // drag-release settled on two different springs; there is only one
                        // mechanism now, so they are the same call and the inconsistency is gone
                        // rather than merely reconciled.
                        //
                        // No velocity argument because no overload takes one (Finding 1), and
                        // nothing is lost: Step 2b already established the release is
                        // velocity-free, and `animateTo` seeds from `oldVelocity`, which is
                        // `ZeroVelocity` when there is no running `currentAnimation` to inherit
                        // from (Transition.kt:751-757) — we were seeking, not animating. Velocity
                        // decides *which* state, via [computeReleaseTarget]; it has not shaped
                        // the curve since Step 2b.
                        //
                        // No `snapTo` afterwards either. The shadow needed one because
                        // AnchoredDraggable settled independently and the transition had to be
                        // told where it had ended up; `animateTo` commits `currentState =
                        // targetState` itself on completion (:791), which is what leaves the
                        // opposite-state rule above pointing somewhere useful for the next drag.
                        //
                        // Launched rather than awaited so the loop goes straight back to
                        // receiving and can interrupt this — see the note above. A
                        // `CancellationException` inside a `launch`ed child is ordinary
                        // cancellation, not a failure, so even in the impossible case that
                        // something did interrupt it via the MutatorMutex, it would end this
                        // animation rather than propagate out and kill the consumer. That is
                        // strictly safer than the arrangement Step 7 shipped.
                        settle = launch { seekableState.animateTo(event.target, releaseSpring) }
                    }
                }
            }
        }
        // ── end the transition and its consumer ──────────────────────────────────────────

        // Publish expansion outward so SynthXBottomNav can slide/shrink/fade in lockstep.
        //
        // SideEffect, not a bare call in the composition body: the old player does
        // `onProgressChanged(progress)` inline (PlayerSheetSkeleton.kt:185), which runs the
        // callback *during* composition. Because the callback writes a snapshot state that
        // other composables read, that is a write-during-composition — the exact pattern
        // Compose warns about, since a composition that has not yet committed can be
        // discarded or replayed, firing the callback more than once or with a value that
        // never reaches the screen.
        //
        // SideEffect is the documented tool for "publish Compose state to non-Compose-managed
        // state": it runs only after a composition successfully commits, so every value the
        // nav receives is one that actually rendered. LaunchedEffect(expansion) would also be
        // correct-by-construction but is the wrong instrument here — it would spin up and
        // cancel a coroutine on every frame of the drag, and the callback would land a
        // dispatch later than the frame it describes, leaving the nav visibly one frame
        // behind the finger. SideEffect is synchronous with the commit and allocates nothing.
        SideEffect { onProgressChanged(expansion) }

        // Complement of SynthXBottomNav's own collapse: the nav slides down by
        // BottomNavHeight * expansion while this shrinks by BottomNavHeight * (1 - expansion),
        // so the two edges stay in contact at every intermediate value instead of drifting.
        // Sharing the BottomNavHeight constant (rather than restating 71.dp) is what keeps
        // that true — see PlayerChromeMetrics.kt.
        val bottomInset = lerpDp(BottomNavHeight, 0.dp, expansion)

        // The sheet's height at expansion 1, captured here rather than read at the call site
        // below. As of Step 5 it is applied on the AnimatedVisibility node down inside the
        // sheet Box's content lambda, which nests two more scopes (BoxScope, then
        // AnimatedVisibilityScope) under this BoxWithConstraintsScope; naming the value once up
        // here beats a qualified receiver down there, and gives it a name that says which
        // height it is rather than leaving a bare `maxHeight` to be read as the sheet's live
        // height. Deliberately *not* the live sheetHeight — see [PlayerV2ExpandedContent].
        val expandedSheetHeight = maxHeight

        // The three animated properties, all driven off the one expansion term so they cannot
        // drift out of step with each other or with the finger.
        val sheetHeight = lerpDp(CollapsedPlayerHeight, expandedSheetHeight, expansion)
        // 24dp on the top corners only, deliberately breaking the app's 2dp shapes.small
        // rule — the documented Apple-Music-style exception, re-confirmed in the design doc
        // (§8) rather than accidentally normalized.
        val cornerRadius = lerpDp(0.dp, 24.dp, expansion)
        // Lerped rather than flat: with no content whatsoever, this shift is the only way to
        // read expansion mid-drag at a glance, and it costs one line and one import.
        val surfaceColor = lerp(Surface, SurfaceVariant, expansion)

        // Outer wrapper whose only job is to hold the inset, exactly as the old player does
        // (PlayerSheetSkeleton.kt:257). The padding shrinks the coordinate space the sheet is
        // bottom-aligned *within*, rather than being applied to the sheet itself — so the
        // sheet's own .align(BottomCenter) below lands it above the nav with no change to its
        // alignment or height logic. At expansion = 0 the space stops BottomNavHeight short of
        // the bottom and the 64dp collapsed bar sits on top of the nav's upper edge; at
        // expansion = 1 the inset is 0 and the sheet reclaims the full height the nav vacated.
        Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)) {
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
                    // Keyed on the travel, not on Unit. Through Step 7 this block only ever
                    // touched `draggableState`, a stable object whose anchors were refreshed
                    // from outside, so `Unit` was correct. Step 8 made it read
                    // `collapsedAnchorPx` and [releaseAnchors] directly, and a `pointerInput`
                    // that never restarts would go on using the height measured at first
                    // composition — every delta scaled by a stale travel after a rotation or a
                    // window resize. Keying on the one value both are derived from restarts the
                    // detector exactly when, and only when, that geometry actually changes.
                    .pointerInput(collapsedAnchorPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Nothing can be computed against a zero-width travel: the delta →
                            // expansion conversion below would divide by zero and produce NaN,
                            // and NaN fails seekTo's `fraction in 0f..1f` precondition as hard
                            // as an out-of-range number does. Unreachable in practice — this box
                            // fills the screen — but the alternative to one line here is a crash
                            // rather than a glitch.
                            if (collapsedAnchorPx <= 0f) return@awaitEachGesture
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
                                // `seekableState.currentState` replaces
                                // `draggableState.currentValue` as the "where are we settled"
                                // read, and it is the exact analogue: `currentState` only moves
                                // on `snapTo`/`animateTo` completion (Transition.kt:574, :791),
                                // never on a seek, so between gestures it holds the settled
                                // state and nothing else.
                                //
                                // The event is the same [PlayerV2GestureEvent.Settle] a release
                                // sends, which is what finally makes tap and release agree.
                                // Step 7 had to leave the tap on `draggableState.animateTo` —
                                // its scope was the release — so a tap and a drag-release
                                // settled on two different springs in two different spaces. One
                                // mechanism now, one call, one spring.
                                //
                                // **The `down.isConsumed` guard is still missing, and is still
                                // deferred — but its reason has changed.** PlayerSheetSkeleton
                                // guards this branch on `!down.isConsumed` so a tap on a child
                                // button (mini play/pause, seek bar, prev/next, 3-dot, queue)
                                // does not also expand the sheet. The old comment here deferred
                                // the fix "until after Stage 2.4, since this gesture detector
                                // changes shape again at that point". That reshape is this step,
                                // so that reason has expired and the comment would now be
                                // pointing at a stage in the past.
                                //
                                // Deferred anyway, deliberately and for a different reason: Step
                                // 8's entire success criterion is behavior parity across a
                                // mechanism swap, and adding a tap-behavior change to the same
                                // diff would put two variables into that comparison — the same
                                // discipline that kept Step 2b to one line. It is now a plain
                                // one-line follow-up with nothing blocking it, and should be the
                                // next task once parity here is confirmed on device.
                                if (seekableState.currentState == PlayerV2State.Collapsed) {
                                    gestureEvents.trySend(
                                        PlayerV2GestureEvent.Settle(PlayerV2State.Expanded),
                                    )
                                }
                            } else {
                                // Drag. Accumulate *expansion* — direction-free, 0..1, exactly
                                // what the sheet shows — and send it. The conversion to a
                                // transition fraction, and with it the only inversion in the
                                // whole mechanism, lives in the consumer; this loop knows
                                // nothing about Collapsed-vs-Expanded or which way the
                                // transition is currently pointed.
                                //
                                // Accumulated here rather than re-read from `expansionState` on
                                // each delta. `expansionState` is the *rendered* value and lags
                                // the finger by a frame; feeding each delta onto a lagging base
                                // would compound that lag into visible rubber-banding. Reading
                                // it once, at drag start, is the analogue of AnchoredDraggable's
                                // `offset` being the state's own accumulator rather than
                                // anything measured back off the screen.
                                //
                                // Sign: `positionChange().y` is positive downward and the offset
                                // this replaces grew downward too, so expansion moves *against*
                                // it — dragging up (negative y) expands. The `/ collapsedAnchorPx`
                                // is the same normalization the deleted `progressState` did, just
                                // applied per-delta instead of to an absolute offset.
                                //
                                // `coerceIn` on every step is the producer-side clamp Finding 4
                                // demands, and it doubles as AnchoredDraggable's own behavior:
                                // `newOffsetForDelta` coerced the offset into the anchor range,
                                // so overdragging past an end never banked a debt that had to be
                                // paid back before the sheet moved again. Clamping the
                                // accumulator preserves that exactly.
                                velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                                var dragExpansion =
                                    (expansionState.value - overSlopDelta / collapsedAnchorPx)
                                        .coerceIn(0f, 1f)
                                gestureEvents.trySend(PlayerV2GestureEvent.Seek(dragExpansion))
                                verticalDrag(drag.id) { change ->
                                    dragExpansion =
                                        (dragExpansion - change.positionChange().y / collapsedAnchorPx)
                                            .coerceIn(0f, 1f)
                                    gestureEvents.trySend(
                                        PlayerV2GestureEvent.Seek(dragExpansion),
                                    )
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                }

                                // Release, still the two explicitly separated halves Step 2a
                                // split `settle(velocity)` into: pick a target, then animate to
                                // it. Only the two *inputs* to the picking changed at Step 8 —
                                // the logic in [computeReleaseTarget] is byte-for-byte the port
                                // Step 2a made and Step 2b left alone.
                                //
                                //   - `anchors` was `draggableState.anchors`, the live copy the
                                //     state held. It is now [releaseAnchors], the same
                                //     `DraggableAnchors` value, remembered here instead of
                                //     pushed into a state.
                                //   - `currentOffset` was `draggableState.requireOffset()`. It
                                //     is now the accumulator converted back to that same offset
                                //     axis. This is an exact identity, not an approximation:
                                //     `progressState` defined expansion as
                                //     `(collapsedAnchorPx - offset) / collapsedAnchorPx`, whose
                                //     inverse is precisely the line below, and `dragExpansion`
                                //     is the same number `dispatchRawDelta` used to accumulate
                                //     into `offset` — same deltas, same clamp, same axis, just
                                //     normalized. So the positional branch still measures from
                                //     the anchor behind the direction of travel, and the
                                //     hysteresis it produces (commit to expanding at 0.4 when
                                //     moving up, hold expanded until 0.6 when moving down) is
                                //     unchanged.
                                //
                                // Velocity is untouched and still the real thing: px/s from
                                // [VelocityTracker], compared against a 200dp/s threshold in the
                                // same units. That is the other half of why the offset axis was
                                // worth keeping — see [releaseAnchors].
                                val velocity = velocityTracker.calculateVelocity().y
                                val releaseTarget = releaseAnchors.computeReleaseTarget(
                                    currentOffset = collapsedAnchorPx * (1f - dragExpansion),
                                    velocity = velocity,
                                    positionalThreshold = positionalThreshold,
                                    velocityThreshold = velocityThreshold,
                                )
                                gestureEvents.trySend(
                                    PlayerV2GestureEvent.Settle(releaseTarget),
                                )
                            }
                        }
                    },
            ) {
                // Migration Step 5: the expanded content's presence is the transition's job
                // now, not a hand-written `expansion > 0f` boolean.
                //
                // **Presence is unchanged, frame for frame.** AnimatedVisibility composes when
                // `visible(targetState) || visible(currentState) || … || isSeeking ||
                // hasInitialValueAnimations` (AnimatedVisibility.kt:808-815). Traced against
                // this file's shadow driver, that lands on exactly the same frames the old gate
                // did: the shadow's first `seekTo(fraction, Expanded)` of an expand drag sets
                // targetState = Expanded, so content composes on the drag's first frame just as
                // `expansion > 0f` did; a collapse drag keeps currentState = Expanded for its
                // whole length; and the shadow's `snapTo(Collapsed)` — which fires on precisely
                // the frame `expansion` reaches 0f — drops both states to Collapsed and
                // disposes. The two gates flip on the same frame in both directions.
                //
                // Not `isSeeking`, despite the name: for a top-level transition backed by a
                // SeekableTransitionState that flag is never actually set. Its one writer is
                // `setPlaytimeAfterInitialAndTargetStateEstablished` (Transition.kt:1284), a
                // @RestrictTo tooling entry point; the real seek path is
                // `seekToFraction() → transition.seekAnimations()` (:845-848, :1384), which
                // never touches it. Worth knowing because it means the framework treats this as
                // an ordinary state-driven transition, and `targetEnterExit` takes its
                // non-seeking branch (:957-977) — the `hasBeenVisible`/PostExit one.
                transition.AnimatedVisibility(
                    visible = { it == PlayerV2State.Expanded },
                    // The placement/sizing half of what used to be PlayerV2ExpandedContent's
                    // own modifier chain — see that composable's doc for why it had to move and
                    // why the split is size-neutral. `.align` is a BoxScope member and resolves
                    // here because this lambda's receiver is still the sheet Box; the node it
                    // tags is the single Layout AnimatedVisibility emits
                    // (AnimatedVisibility.kt:855-877), a direct child of that Box, so the
                    // BoxChildDataElement parent data reaches the Box's measure policy exactly
                    // as it did from the Column.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // The sheet's *fully expanded* height, not the live sheetHeight — this
                        // is what makes the expanded layout static. requiredHeight (not height)
                        // so the shorter sheet's incoming maximum is ignored and the container
                        // overhangs upward, to be clipped by the sheet Box's own .clip().
                        .requiredHeight(expandedSheetHeight)
                        // Cancels bottomInset back out of this container's placement: it is
                        // welded to the sheet's bottom edge, and that edge descends as the
                        // padding on the sheet's parent Box relaxes from BottomNavHeight to
                        // 0dp across the drag. Without this the content would drift down by up
                        // to BottomNavHeight as the nav slides out from under the sheet.
                        // Positive Y, same sign as PlayerSkeletonControlsOverlay.kt's identical
                        // correction on the old player.
                        //
                        // Stage 2.4b-2: was a plain `.offset(y = bottomInset)`. Replaced with
                        // [motionFrameOffsetY] — same visual placement, but tags this node as
                        // motion rather than structure so the coming shared-element art doesn't
                        // see this live offset as a re-target signal. See that function's doc.
                        .motionFrameOffsetY(bottomInset),
                    // Nothing. The reveal clip is what makes this content appear, and it
                    // already runs off `expansion` from the drag's first frame — an enter
                    // transition would be a second, redundant appearance animation layered on
                    // top of the envelope, which is precisely what design doc §2 replaced.
                    // EnterTransition.None is also free of Finding 3's disposal hazard: that
                    // concern is specific to *exit* (content being dropped before the clip can
                    // close over it); there is no enter-side equivalent.
                    enter = EnterTransition.None,
                    // ExitTransition.None would dispose the content on the first frame of a
                    // collapse and leave EnvelopeRevealShape nothing to clip (Step 0's Finding
                    // 3). fadeOut(targetAlpha = 1f) is the substitute the plan specifies: a
                    // hold, not a fade. Confirmed inert from source — the alpha child animation
                    // targets `enter.data.fade?.alpha ?: 1f` at PreEnter, 1f at Visible, and
                    // `exit.data.fade.alpha` = 1f at PostExit (EnterExitTransition.kt:1180-1184),
                    // so it is the constant 1f in every state, and with no changeSize on either
                    // side `shouldAnimateSizeChange` is false (:956-957) so the node performs no
                    // size or offset animation either.
                    //
                    // The duration must be exactly PlayerV2TransitionMs, and this is the first
                    // place that constraint bites: `calculateTotalDurationNanos` recurses into
                    // child transitions and takes the max (Transition.kt:1161-1168), and this
                    // AnimatedVisibility is a child transition. A longer spec here would raise
                    // totalDurationNanos above the `expansion` tween's duration and silently
                    // break the fraction↔expansion 1:1 identity every seekTo depends on. The
                    // enter direction resolves to DefaultAlphaAndScaleSpring (:1172) rather
                    // than this spec, which is harmless: a spring from 1f to 1f at rest has
                    // zero duration and cannot raise the max.
                    exit = fadeOut(
                        animationSpec = tween(PlayerV2TransitionMs, easing = LinearEasing),
                        targetAlpha = 1f,
                    ),
                ) {
                    PlayerV2ExpandedContent(
                        song = song,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        onPlayPauseClick = onPlayPauseClick,
                        currentPosition = currentPosition,
                        duration = duration,
                        onSeek = onSeek,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onPreviousClick = onPreviousClick,
                        onNextClick = onNextClick,
                        progress = expansionState,
                        // PROBE 2.4b-2 (TEMPORARY DEBUG — revert before 2.4b-3 lands the real
                        // wiring): a throwaway sharedElement, same discipline as the reverted
                        // Stage 2.4b-0 probe, to confirm on-device that totalDurationNanos (see
                        // the SideEffect near the end of this function) now stays bounded
                        // through a full expand/collapse drag rather than climbing.
                        probeArtModifier = Modifier.sharedElement(
                            rememberSharedContentState(key = "PROBE_2_4b2_player_album_art"),
                            animatedVisibilityScope = this,
                        ),
                    )
                }
            }
        }
        // Migration Step 6 introduced this AnimatedVisibility; Stage 2.4b-2 relocated it here,
        // as a sibling of the sheet Box above rather than a child of it.
        //
        // **There was no boolean gate here to replace at Step 6.** Unlike the expanded content —
        // which Stage 2.3 gated on `expansion > 0f` — the mini content was
        // *unconditionally composed*, for the whole life of the host, with only its
        // opacity (the `graphicsLayer` alpha inside [PlayerV2MiniContent]) and its
        // hit-testing (`miniContentEnabled`) driven by progress. So Step 6 did not
        // move a gate from one mechanism to another; it introduced a composition
        // lifecycle where previously there was none — Stage 2.4b needs an
        // `AnimatedVisibilityScope` on *this* side of the transition to hang the mini
        // album art's `Modifier.sharedElement` off, and only `Transition.AnimatedVisibility`
        // produces one.
        //
        // **Why this moved out of the sheet Box at Stage 2.4b-2.** Through Step 6 this sat
        // inside the sheet's own `Box`, `.align(Alignment.TopCenter)` against that Box's live
        // `sheetHeight` — so the mini row's top edge rose with the sheet as it expanded, purely
        // as a side effect of sharing a container with something that resizes. Design doc §4
        // says mini content "simply fades... no reveal treatment," and §3 restricts all
        // per-frame position/size animation to the album art's eventual shared-element morph —
        // the mini row was never meant to move at all. It also made the mini album art's frame
        // a moving lookahead target for exactly the same reason the expanded art's frame was:
        // Stage 2.4b-0's probe found this exact container's live resize is what sends
        // `totalDurationNanos` climbing once a `sharedElement` is attached, because
        // `SharedElement.onLookaheadPlaced`'s `structuralOffset` changes every frame the sheet
        // is resizing (see [motionFrameOffsetY]'s doc for the same mechanism on the expanded
        // side). Being a sibling of the sheet rather than a child fixes this outright, rather
        // than needing a `withMotionFrameOfReferencePlacement` workaround the way the expanded
        // side does — there's no live offset here left to exclude, because there's nothing left
        // that varies.
        //
        // Position: `BottomCenter` + a **fixed** `-BottomNavHeight`, not the live `bottomInset`.
        // `BottomNavHeight` is exactly the collapsed bar's resting inset (`bottomInset` at
        // expansion = 0 — see `bottomInset`'s own `lerp(BottomNavHeight, 0.dp, expansion)`
        // above), so this reproduces the mini bar's on-screen position at rest and then holds
        // it there for the mini row's entire visible span, rather than tracking `bottomInset`'s
        // small drift toward 0 as expansion approaches the ≈0.333 point where the row is
        // already fully faded out. Using the live value here would have been a smaller version
        // of the exact bug this stage exists to remove.
        //
        // **What actually changes on screen: nothing**, same as Step 6's own claim. The row's
        // fixed frame at expansion = 0 is numerically identical to where `.align(TopCenter)`
        // inside the sheet Box put it before (the sheet's own height is `CollapsedPlayerHeight`
        // at rest, so its top edge and this fixed frame's top edge coincide); the divergence
        // only exists while dragging, and by ≈0.333 of that drag the row is already invisible.
        transition.AnimatedVisibility(
            visible = { it == PlayerV2State.Collapsed },
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = -BottomNavHeight),
            // Nothing, same as Step 5 and for the same reason: the visible appearance
            // is the `graphicsLayer` alpha's job and it is already correct from the
            // first frame. Finding 3's disposal hazard is exit-side only, so there is
            // no enter-side substitute to reach for.
            enter = EnterTransition.None,
            // The plan's "same inert-exit treatment" — a hold, not a fade. Two separate,
            // complementary mechanisms, and it matters that they stay separate: *this*
            // spec governs only how long the node survives after the state flips
            // (`shouldDisposeBlock = { current, target -> current == target && target
            // == PostExit }`, AnimatedVisibility.kt:780), while the visible opacity
            // curve stays the `(1f - progress * 3f)` graphicsLayer fade inside
            // [PlayerV2MiniContent]. Substituting a real `fadeOut` here would replace a
            // curve that completes by progress ≈ 0.333 with one keyed to the
            // transition's whole span — a visible behavior change, and the exact thing
            // the plan's Step 6 line rules out.
            //
            // Duration must be PlayerV2TransitionMs for the same reason it must be on
            // Step 5's exit: this is a child transition, `calculateTotalDurationNanos`
            // takes the max over children (Transition.kt:1161-1168), and anything
            // longer would lift `totalDurationNanos` above the `expansion` tween and
            // silently break the fraction↔expansion 1:1 identity every `seekTo`
            // depends on. Inert in value terms regardless: alpha targets 1f in every
            // one of PreEnter/Visible/PostExit (EnterExitTransition.kt:1180-1184), and
            // with no `changeSize` on either side `shouldAnimateSizeChange` is false
            // (:956-957), so no size or offset animation is created either.
            exit = fadeOut(
                animationSpec = tween(PlayerV2TransitionMs, easing = LinearEasing),
                targetAlpha = 1f,
            ),
        ) {
            PlayerV2MiniContent(
                song = song,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                onPlayPauseClick = onPlayPauseClick,
                progress = expansionState,
                // PROBE 2.4b-2 (TEMPORARY DEBUG — revert before 2.4b-3 lands the real wiring):
                // see the matching probe at the PlayerV2ExpandedContent call site above for what
                // this is verifying.
                probeArtModifier = Modifier.sharedElement(
                    rememberSharedContentState(key = "PROBE_2_4b2_player_album_art"),
                    animatedVisibilityScope = this,
                ),
            )
        }
        // PROBE 2.4b-2 (TEMPORARY DEBUG — revert before 2.4b-3 lands the real wiring): confirms
        // totalDurationNanos stays bounded near PlayerV2TransitionMs throughout a full drag now
        // that both album art frames are lookahead-static, rather than climbing the way Stage
        // 2.4b-0's probe found before this stage's restructure. Remove this SideEffect, the two
        // `probeArtModifier` call sites above/below, and the `probeArtModifier` parameters on
        // PlayerV2MiniContent/PlayerV2ExpandedContent once confirmed on-device.
        SideEffect {
            Log.d(
                "PlayerV2Probe2_4b2",
                "totalDurationMs=${transition.totalDurationNanos / 1_000_000} " +
                    "expansion=$expansion fraction=${seekableState.fraction} " +
                    "currentState=${seekableState.currentState} " +
                    "targetState=${seekableState.targetState}",
            )
        }
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
private fun <T> DraggableAnchors<T>.computeReleaseTarget(
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
 * Deliberate breathing room between the top system inset and the album art region — and
 * *only* that. It is not a status-bar allowance: the real inset is read separately from
 * [WindowInsets.safeDrawing] and added to this (see [PlayerV2ExpandedContent]).
 *
 * That separation is the whole point of the constant. The old (detached) player's
 * `ExpandedTopMargin = 32.dp` (PlayerSheetSkeleton.kt:68) was one number silently doing both
 * jobs, which is why the old expanded sheet's top edge sat *under* the status bar on every
 * device — there was no inset term anywhere in that layout. Design doc §8 lists fixing this
 * as in scope for the rebuild rather than something to carry over.
 */
private val ExpandedArtTopMargin: Dp = 24.dp

/**
 * Left/right inset of the expanded content column, and the single source of the expanded
 * album art's width.
 *
 * The art is centered at `maxWidth - ExpandedContentHorizontalInset * 2`, so its left edge
 * lands exactly here; [PlayerV2TitleRow] uses the same value as its start padding so the
 * title text's left edge lines up with the art's. Declaring it once is deliberate — the old
 * player's art inset (`maxWidth - Spacing.HorizontalPadding * 4`, QueueArtPositioning.kt:58)
 * and its title row's start padding (`Spacing.HorizontalPadding` + `Spacing.lg` = 40dp,
 * PlayerSkeletonExpandedContent.kt:47,53) were independently written constants that happened
 * to disagree by 8dp, which is exactly the class of drift this stage exists to remove.
 *
 * Numerically identical to the old `Spacing.HorizontalPadding * 4` art width, so the art
 * itself is unchanged in size — only the title now agrees with it.
 *
 * Note this alignment is exact only while the art is width-bound (every normal portrait
 * phone). On a short/landscape screen the art clamps to the available height and, being
 * centered, its edges move inward; the title row stays at this fixed inset.
 */
private val ExpandedContentHorizontalInset: Dp = Spacing.HorizontalPadding * 2

/**
 * Stage 2.2 built real expanded content — title/artist + 3-dot menu near the top, seek bar +
 * transport + queue button near the bottom — statically laid out via a Column, not the old
 * (detached) PlayerSkeletonControlsOverlay's
 * progress-driven slide/fade/reveal-clip wrapper; only its *inner* content (the seek bar,
 * transport row, and queue row themselves) is mirrored here.
 *
 * Stage 2.3 adds the envelope reveal: [EnvelopeRevealShape], clipped onto this Column as the
 * single container the design doc (§2) calls for, grows the visible region bottom-up as
 * [progress] climbs — content is drawn once at its final position and revealed, never
 * animated in place. The call site decides whether this composable exists in the tree at all —
 * as of migration Step 5 that is a `Transition.AnimatedVisibility(visible = { it == Expanded })`
 * rather than Stage 2.3's `progress > 0f` boolean, on the same frames in both directions; once
 * present, this clip decides how much of it is visible. The fact that this block renders
 * underneath the mini content
 * whenever both are visible in the same frame is unrelated to the reveal and still expected —
 * z-order layering between the two is out of scope for this stage.
 *
 * Stage 2.3b makes two changes on top of that.
 *
 * First, the container is no longer `fillMaxSize`. It is [requiredHeight] at the sheet's fully
 * expanded height — constant for the screen — and bottom-anchored inside the sheet Box. (Those
 * three modifiers now live on the [PlayerV2Host] call site's AnimatedVisibility node rather than
 * on this Column; see the modifier note below.) Under `fillMaxSize` this Column was re-measured
 * against the live `sheetHeight` on every frame of a drag, which was harmless while the only
 * flexible child was a spacer but would have resized the album art (and re-triggered Coil)
 * once-per-frame. Design doc §1 asks for the expanded layout to be "laid out once, at fixed
 * final positions… not re-derived per animation frame"; this is what actually delivers that.
 * The visible consequence is that the content no longer slides with the sheet's top edge as
 * the sheet grows — it holds still and is revealed, which is the §2 envelope model taken
 * literally.
 *
 * Second, the 320dp placeholder spacer is gone, replaced by a real album art frame sized off
 * Column's own measurement of the title row and controls block. See the inline comments.
 *
 * The art deliberately stays *inside* this clipped Column rather than being split out as an
 * unclipped sibling, which is where design doc §2 will eventually want it. At this stage the
 * art has no independent animation, so there is nothing for the clip to interfere with, and
 * the split only becomes load-bearing at Stage 2.4b — where it arrives for free: a matched
 * `Modifier.sharedElement` renders into the SharedTransitionScope's overlay specifically, per
 * its own API docs, "to avoid being faded in/out along with its parents or clipped by its
 * parent". Outside an active transition the clip is either fully open (settled expanded) or
 * this composable is not in the tree at all (settled collapsed), so drawing in place is never
 * wrong in the meantime.
 *
 * The queue button's [onClick][DragResistantIconButton] is a no-op: queue mode doesn't exist
 * until Stage 4, so there is nothing to wire it to yet, but it's laid out in its final
 * position now so Stage 4 only has to attach behavior, not insert a new element.
 *
 * **Migration Step 5 — the modifier split, and why it is geometry-neutral.** This composable's
 * outer Column used to carry
 * `fillMaxWidth → requiredHeight(expandedHeight) → align(BottomCenter) → offset(y = bottomInset)
 * → clip(revealShape)`, with `expandedHeight` and `bottomInset` as parameters and [BoxScope] as
 * the receiver (for `align`). `AnimatedVisibility`'s content lambda is an
 * `AnimatedVisibilityScope`, not a `BoxScope`, so the three placement/sizing modifiers moved out
 * to the `AnimatedVisibility` call itself and both parameters went with them; only
 * `fillMaxWidth` and `clip` stayed. The receiver went away entirely.
 *
 * The split is size- and position-neutral, traced through the framework rather than assumed:
 * `AnimatedVisibility` emits exactly one `Layout` node with the caller's modifier applied first
 * (`AnimatedVisibility.kt:855-877`), and its measure policy passes the incoming constraints to
 * its child untouched, reports `max(children)` as its own size, and places at `(0, 0)`
 * (:896-916). So `requiredHeight` on that node fixes the constraints the Column is measured
 * with, the Column's `fillMaxWidth` still resolves against the sheet Box's width, the node
 * reports the Column's exact size back up, and `align`/`offset` position that node precisely
 * where they positioned the Column. `EnvelopeRevealShape` therefore still reads the same
 * `size.height` it always did. The one node that could have perturbed this — the
 * `EnterExitTransitionElement` from `createModifier` — is inert here because neither the enter
 * nor the exit carries a `changeSize`, so `shouldAnimateSizeChange` is false and no size or
 * offset animation is created (`EnterExitTransition.kt:956-957`).
 *
 * One genuinely new behavior, harmless today: `AnimatedVisibilityImpl` wraps the caller's
 * modifier in a `layout` that reports `IntSize.Zero` **during lookahead** while the target state
 * is not visible (`AnimatedVisibility.kt:766-776`). This subtree *is* inside a lookahead scope —
 * Step 1 wrapped it in `SharedTransitionLayout` — so on a collapse drag this node's lookahead
 * size is zero while its approach (real) size is unchanged. Nothing reads lookahead geometry
 * here yet, so there is no visible effect; it becomes load-bearing at Stage 2.4b, when the album
 * art's `sharedElement` starts deriving its target bounds from exactly that pass.
 */
@Composable
private fun PlayerV2ExpandedContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    progress: State<Float>,
    // PROBE 2.4b-2 (TEMPORARY DEBUG parameter — revert before 2.4b-3 lands the real wiring):
    // applied to a Box wrapping the art below, purely so the probe's throwaway sharedElement
    // doesn't require changing PlayerAlbumArt's own signature.
    probeArtModifier: Modifier = Modifier,
) {
    // Keyed on progress's own identity (stable for this composable's whole lifetime — as of
    // migration Step 4 it's the same animateFloat TransitionAnimationState instance from
    // PlayerV2Host on every recomposition, `remember(this)`-scoped to the transition itself,
    // Transition.kt:2119-2120), so this Shape instance is built once, not once per frame.
    val revealShape = remember(progress) { EnvelopeRevealShape(progress) }

    // Read as a plain Dp rather than applied via Modifier.statusBarsPadding(): this value is
    // a term in the art's height budget below, and a padding modifier would shrink the
    // Column's usable height invisibly from outside the arithmetic. safeDrawing rather than
    // statusBars so a display cutout taller than the status bar is not under-reported —
    // identical on non-cutout devices. Un-consumed by the time it reaches here: the only
    // inset consumed above this point is BottomChromeOverlay's navigationBarsPadding()
    // (SynthXBottomNav.kt:115), and SynthXNavHost's consumeWindowInsets is on a sibling
    // subtree, not an ancestor.
    val topInset = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Top)
        .asPaddingValues()
        .calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // All this Column keeps as of Step 5. The `.requiredHeight(expandedHeight)
            // .align(BottomCenter).offset(y = bottomInset)` that used to sit between
            // fillMaxWidth and clip now lives on the AnimatedVisibility node one level up —
            // see this composable's doc for why, and why the geometry is unchanged.
            .clip(revealShape),
    ) {
        Spacer(modifier = Modifier.height(topInset + ExpandedArtTopMargin))
        // The art frame. Sized by Column's own measurement rather than by any budget
        // arithmetic: Column measures its zero-weight children first (the title row and the
        // controls block below, each at whatever height it actually needs at the current font
        // scale) and hands this weighted child exactly what is left
        // (RowColumnMeasurePolicy.kt — "First measure children with zero weight", then
        // `mainAxisMax = childMainAxisSize` for the weighted pass). BoxWithConstraints reads
        // that remainder back as a real Dp.
        //
        // This is the direct replacement for the old player's two hand-written height
        // budgets, which partitioned the same space and disagreed by exactly
        // ExpandedTopMargin — `artMaxHeight` (QueueArtPositioning.kt:59) omitted the top
        // margin that `expandedRegionHeight` (:81) subtracted, so on any screen where height
        // was the binding constraint the art overran its region by 32dp and the controls
        // overlay painted over the bottom of the artist line. There is only one budget now
        // and Compose owns it.
        //
        // It also removes the two sp-as-dp constants that fed those budgets
        // (TitleRowReservedHeight = 52.dp is titleLarge's 28sp + bodyLarge's 24sp line
        // heights written down as dp; ControlsOverlayHeight = 300.dp over-reserved the real
        // ~240dp block by 60dp). Measuring the real composables is correct at every font
        // scale; neither constant was.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // Square, capped by whichever axis binds. maxHeight here is the genuine leftover
            // space, so the cap needs no separate derivation.
            val side = min(maxWidth - ExpandedContentHorizontalInset * 2, maxHeight)
            // PROBE 2.4b-2 (TEMPORARY DEBUG — revert before 2.4b-3): wrapping Box carries the
            // throwaway sharedElement rather than changing PlayerAlbumArt's signature.
            Box(modifier = probeArtModifier) {
                PlayerAlbumArt(
                    song = song,
                    isPlaying = isPlaying,
                    size = side,
                    // The mini bar's 44dp thumbnail would visibly upscale at this size.
                    fullResolution = true,
                )
            }
        }
        PlayerV2TitleRow(song = song)
        Spacer(modifier = Modifier.height(Spacing.lg))
        PlayerV2ControlsBlock(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onPlayPauseClick = onPlayPauseClick,
            currentPosition = currentPosition,
            duration = duration,
            onSeek = onSeek,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPreviousClick = onPreviousClick,
            onNextClick = onNextClick,
        )
    }
}

/**
 * Stage 2.3's envelope reveal clip (design doc §2) — a rectangle grown bottom-up as
 * [progress] climbs from 0f (nothing revealed) to 1f (the whole container revealed), so
 * content already drawn at its final position becomes visible edge-first from the bottom,
 * matching the sheet's own bottom-up growth ([PlayerV2Host]'s `sheetHeight` lerp) rather than
 * fading or sliding in place.
 *
 * [progress] is read inside [createOutline] — called from [Shape], not from this class's own
 * constructor or from any `@Composable` body — so the read happens where Compose UI's
 * graphics-layer machinery invokes it: inside `NodeCoordinator.updateLayerParameters()`'s
 * `snapshotObserver.observeReads(...)` block (`GraphicsLayerScope.updateOutline()` calls
 * `shape.createOutline(...)` there — verified against the actual `ui-android:1.12.0` sources,
 * the version this module builds against). That observer is scoped to the layer/coordinator,
 * entirely separate from the composition's own snapshot observer: a change to [progress]
 * re-runs only that layer's parameter update (recomputing the outline and redrawing), the
 * same phase-deferred path `Modifier.graphicsLayer { }` reads are documented to use, since
 * `Modifier.clip(shape)` is defined as `graphicsLayer(shape = shape, clip = true)`
 * (`androidx.compose.ui.draw.Clip.kt`). No `@Composable` function re-executes when progress
 * changes because of this shape — this class holds no snapshot-state read anywhere outside
 * [createOutline].
 */
private class EnvelopeRevealShape(private val progress: State<Float>) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val revealedHeight = size.height * progress.value.coerceIn(0f, 1f)
        return Outline.Rectangle(
            Rect(
                left = 0f,
                top = size.height - revealedHeight,
                right = size.width,
                bottom = size.height,
            ),
        )
    }
}

/**
 * Title/artist + 3-dot menu — mirrors PlayerSkeletonExpandedContent.kt's inner Row exactly
 * (same padding, same text styles, same marquee-on-both). [MoreOptionsButton] is reused as-is
 * (it's `internal`, same module) rather than duplicated: it already reads
 * [com.v1per.synthx.ui.components.LocalSongActions], which is live here via
 * [com.v1per.synthx.ui.components.SongActionsHost] wrapping BottomChromeOverlay in
 * MainScaffold, so this needs no new host or wiring.
 */
@Composable
private fun PlayerV2TitleRow(song: Song) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Start is [ExpandedContentHorizontalInset] (32dp) so the title's left edge lines
            // up with the album art's — the old pair of stacked paddings
            // (Spacing.HorizontalPadding + Spacing.lg) put it at 40dp against art at 32dp, an
            // 8dp disagreement that was invisible only because there was no art on screen yet.
            // End is unchanged at 28dp (Spacing.HorizontalPadding + 12dp): it trails
            // MoreOptionsButton, not the art, so it has no reason to match.
            .padding(start = ExpandedContentHorizontalInset, end = Spacing.HorizontalPadding + 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
        MoreOptionsButton(song = song, enabled = true)
    }
}

/**
 * Seek bar + transport + queue button — mirrors PlayerSkeletonControlsOverlay.kt's inner
 * content (sizes, icons, [Alpha.Disabled] dimming) with fixed [Spacing] gaps standing in for
 * that file's `weight(1f)` spacers, which only made sense inside its fixed-height,
 * progress-driven outer Column (not present here — see [PlayerV2ExpandedContent]'s doc).
 */
@Composable
private fun PlayerV2ControlsBlock(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerSeekBar(
            currentPosition = currentPosition,
            duration = duration,
            onSeek = onSeek,
            enabled = true,
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(2f))
            DragResistantIconButton(
                onClick = onPreviousClick,
                size = 72.dp,
                enabled = hasPrevious,
            ) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = stringResource(R.string.cd_previous_track),
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(if (hasPrevious) 1f else Alpha.Disabled),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            DragResistantIconButton(
                onClick = onPlayPauseClick,
                size = 96.dp,
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = TextPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) {
                            stringResource(R.string.cd_pause)
                        } else {
                            stringResource(R.string.cd_play)
                        },
                        tint = if (isPlaying) Accent else TextPrimary,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            DragResistantIconButton(
                onClick = onNextClick,
                size = 72.dp,
                enabled = hasNext,
            ) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = stringResource(R.string.cd_next_track),
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(if (hasNext) 1f else Alpha.Disabled),
                )
            }
            Spacer(modifier = Modifier.weight(2f))
        }
        Spacer(modifier = Modifier.height(Spacing.xl))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            // No-op — queue mode doesn't exist until Stage 4. See this composable's own doc.
            DragResistantIconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = stringResource(R.string.cd_queue),
                    tint = TextSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
    }
}

/**
 * Stage 2.1 built real mini-bar content — art, title/artist, play/pause — statically, with no
 * shared-element art morph yet (§3, still a later stage).
 *
 * This stage adds the fade-as-you-drag design doc §4 calls for: the title/artist/play-pause
 * portion fades out as the sheet grows past mini height, using
 * [PlayerSheetSkeleton.kt:210][com.v1per.synthx.ui.player.PlayerSheetSkeleton]'s exact proven
 * curve (`1f - progress * 3f`, coerced 0..1 — fully visible at progress 0, fully gone by
 * progress ≈ 0.333) rather than inventing a new one. Deliberately *not* symmetric with
 * [PlayerV2ExpandedContent]'s reveal, which starts opening from `progress > 0f` — the two
 * overlapping for roughly the first third of the drag is expected, not a bug: design doc §4
 * calls mini content "disposable, not part of the envelope content," with no reveal treatment
 * of its own.
 *
 * The album art is deliberately outside the fade: it is pinned at the sheet's top edge and
 * stays fully visible for the whole drag, exactly as before this stage. That's why the fade
 * modifier lands on a new wrapper `Row` around just the title/artist [Column] and the
 * play/pause button, rather than on this composable's own root `Row` the way the old
 * (detached) PlayerSkeletonMiniContent applies its `.alpha(miniContentAlpha)` — that file's
 * root `Row` never actually contained its real art (a `Spacer` there only reserves the art's
 * footprint; the real art is a separately-positioned overlay drawn elsewhere,
 * PlayerSheetSkeleton.kt:392-399), so a whole-`Row` alpha never touched it. `PlayerAlbumArt`
 * here *is* a direct child of the root `Row`, so fading the whole thing would fade the art too
 * — hence the narrower wrapper.
 *
 * **Migration Step 6 — the receiver, and what did *not* change.** This composable was a
 * [BoxScope] extension solely so its root `Row` could call `.align(Alignment.TopCenter)`. It is
 * now called from inside a `Transition.AnimatedVisibility(visible = { it == Collapsed })`, whose
 * content lambda is an `AnimatedVisibilityScope`, so that `align` moved up onto the
 * `AnimatedVisibility` node and the receiver went away with it. Deliberately *not* replaced by an
 * `AnimatedVisibilityScope` receiver, for the reason Step 5 established on
 * [PlayerV2ExpandedContent]: that interface carries no `@Stable`, so taking it as a parameter
 * would make this composable non-skippable. Stage 2.4b will need the scope for the art's
 * `Modifier.sharedElement` and can take it at that point as a narrower, explicitly-typed
 * parameter on the art alone.
 *
 * Neither of this composable's two progress-driven behaviors is affected by that wrapping. The
 * `graphicsLayer` alpha below is unchanged — the plan is explicit that Step 6 keeps the
 * `1f - progress * 3f` curve rather than substituting a real `fadeOut`, because the
 * `AnimatedVisibility` exit governs *composition lifetime* while this governs *opacity*, and
 * they are answering different questions. `miniContentEnabled` is likewise untouched: it is a
 * `derivedStateOf` over the very same `progress` `State<Float>` reference the host passes in,
 * with no dependency on the transition's visibility machinery, so the play/pause hit-test still
 * flips at exactly progress ≈ 0.1667 in both directions. The only way `AnimatedVisibility` could
 * have moved that threshold is by disposing this subtree while the gate still mattered, and it
 * disposes only once `snapTo(Expanded)` commits — five times past the crossing, with the button
 * long since disabled and at alpha 0.
 *
 * @param progress the same `expansionState` reference [PlayerV2Host] hands to
 *   [PlayerV2ExpandedContent] — not the unwrapped value — so the fade alpha below is read
 *   inside [androidx.compose.ui.graphics.graphicsLayer]'s lambda at draw time, the same
 *   phase-deferred, zero-recomposition-cost pattern [EnvelopeRevealShape] already establishes
 *   in this file. Reading `progress.value` directly in this composable's body instead (e.g. to
 *   share one local val between the alpha and the enabled check below) would subscribe this
 *   whole composable to a 60Hz recomposition for the entire drag, not just the ~33% of it where
 *   the fade is actually animating — a cost this composable doesn't currently pay and shouldn't
 *   newly acquire. See the `miniContentEnabled` local below for why `enabled` can't avoid a
 *   composition-phase read the same way, and how that cost is bounded instead.
 */
@Composable
private fun PlayerV2MiniContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    progress: State<Float>,
    // PROBE 2.4b-2 (TEMPORARY DEBUG parameter — revert before 2.4b-3 lands the real wiring):
    // applied to a Box wrapping the art below, purely so the probe's throwaway sharedElement
    // doesn't require changing PlayerAlbumArt's own signature.
    probeArtModifier: Modifier = Modifier,
) {
    // `DragResistantIconButton`'s `enabled` is a plain Boolean composable parameter, not a
    // draw-phase-readable modifier — unlike the graphicsLayer alpha below, there is no way to
    // gate it without a composition-phase read of `progress.value` somewhere. Wrapped in
    // derivedStateOf (mirroring this file's own `isContentPresent` pattern above) rather than
    // a bare `val`, so this composable only recomposes when the *boolean*
    // actually flips — twice per drag, at the progress ≈ 0.1667 crossing — instead of on every
    // one of the ~60 progress ticks a full drag produces. A bare `val miniContentEnabled =
    // (1f - progress.value * 3f).coerceIn(0f, 1f) > 0.5f` would read progress.value in the
    // composition body directly, subscribing this composable to every progress change for the
    // *entire* drag (Compose can't know the post-coerce, post-comparison result is unchanged
    // without derivedStateOf's own value-equality check) — exactly the cost this stage
    // otherwise avoids by keeping the alpha itself draw-phase-only.
    val miniContentEnabled by remember(progress) {
        derivedStateOf { (1f - progress.value * 3f).coerceIn(0f, 1f) > 0.5f }
    }

    Row(
        modifier = Modifier
            // An `.align(...)` used to head this chain (TopCenter through Step 6, then
            // BottomCenter + a fixed offset as of Stage 2.4b-2). It lives on the
            // AnimatedVisibility node at the call site, not here — see this composable's
            // doc, and that call site's own comment for why the move is position-neutral.
            .fillMaxWidth()
            .height(CollapsedPlayerHeight)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // PROBE 2.4b-2 (TEMPORARY DEBUG — revert before 2.4b-3): wrapping Box carries the
        // throwaway sharedElement rather than changing PlayerAlbumArt's signature.
        Box(modifier = probeArtModifier) {
            PlayerAlbumArt(
                song = song,
                isPlaying = isPlaying,
                size = 44.dp,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        // Wraps exactly the title/artist + play/pause portion — see this composable's doc for
        // why the fade can't land on the root Row the way the old player's does. weight(1f)
        // moves here from the inner Column (below) so this wrapper still claims the same
        // leftover horizontal space in the outer Row that the Column alone used to; the
        // Column's own weight(1f) now applies within this wrapper instead, so the button's
        // size and position, and the Column's width, are unchanged from before this stage.
        Row(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    // Same curve as `miniContentEnabled` above, deliberately inlined again
                    // rather than sharing a local val — see this composable's `progress` param
                    // doc. This read happens once per draw/layer update, not once per
                    // recomposition, and costs nothing extra by being duplicated.
                    alpha = (1f - progress.value * 3f).coerceIn(0f, 1f)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DragResistantIconButton(onClick = onPlayPauseClick, enabled = miniContentEnabled) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TextSecondary,
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
                        tint = if (isPlaying) Accent else TextPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}
