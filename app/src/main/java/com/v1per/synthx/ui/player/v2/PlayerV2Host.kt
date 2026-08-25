package com.v1per.synthx.ui.player.v2

import android.util.Log
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
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
 * **Not yet load-bearing.** Nothing reads `expansion` until Step 4, and during a drag the value
 * is driven by `seekTo` rather than by elapsed time, so this duration currently governs no
 * visible motion at all. It first matters at Step 7, where release switches to
 * `SeekableTransitionState.animateTo` and the transition actually runs on a clock — that is the
 * point to re-examine whether 300ms is the right feel, not now.
 */
private const val PlayerV2TransitionMs: Int = Motion.DurationNormal

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
 * Deliberate divergences from docs/player-rebuild-design.md, all scoped to this stage:
 * - The design doc's mechanism is SeekableTransitionState inside a SharedTransitionLayout
 *   (§1). This uses [AnchoredDraggableState] instead, because at two states with no shared
 *   elements and no content to reveal, the transition machinery would buy nothing while the
 *   old player's AnchoredDraggable drag/settle path is already proven on this exact gesture.
 *   Swapping the mechanism is its own later step.
 * - No BackHandler, and no tap-to-collapse. Dragging down is currently the only way back to
 *   Collapsed.
 *
 * Stage 1.2 has since added the nav coordination that this list previously called out as
 * missing (§5): [onProgressChanged] reports the live drag progress outward, and the sheet
 * applies the matching bottom inset so the collapsed bar stacks above the nav rather than
 * overlaying it. Everything else above still stands.
 *
 * Every gesture constant here is copied verbatim from
 * [com.v1per.synthx.ui.player.PlayerSheetSkeleton] rather than re-tuned — the 0.4f
 * positional threshold, the 200dp velocity threshold, exponentialDecay's 1.8f friction
 * multiplier and [Motion.SpringStandard] as the snap spec.
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
 *   style is also what Compose itself uses for scoped composables, and what this file already
 *   does for [BoxScope.PlayerV2MiniContent] / [BoxScope.PlayerV2ExpandedContent].
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val scope = rememberCoroutineScope()
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }
    // Higher friction lets a fling decelerate before handing off to the snap spring.
    //
    // Dead on the release path as of migration Step 2b: the only caller that ever consulted a
    // decay spec was animateToWithDecay, and the release site now calls the plain animateTo.
    // Kept because the deprecated AnchoredDraggableState constructor below requires the
    // parameter (it is `lateinit` and the state's `usePreModifierChangeBehavior` check reads
    // `::decayAnimationSpec.isInitialized`, which is what makes animateTo default to
    // Motion.SpringStandard rather than Foundation's own tween). It goes away with the state
    // itself at Step 8, not before — deleting it here would be an unrelated behavior change
    // smuggled into a one-line experiment.
    val decayAnimationSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }

    // BoxWithConstraints, not fillMaxSize alone: the expanded height target has to be a real
    // measured number (maxHeight) rather than a guess, and the drag anchors need the same
    // number in pixels (constraints.maxHeight) to place the Collapsed anchor. Mirrors how
    // PlayerSheetSkeleton derives both.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()

        // Hoisted out of the AnchoredDraggableState constructor call below so that the release
        // site's own target computation (see computeReleaseTarget) uses the *same two lambda
        // instances* the state itself was configured with, rather than a second copy of 0.4f
        // and 200.dp written out again. The state's own `positionalThreshold` /
        // `velocityThreshold` fields are `internal` in Foundation 1.12.0
        // (AnchoredDraggable.kt:830-831), so reading them back off the state is not possible
        // from app code — hoisting the shared definition upward is the only form "reuse, don't
        // duplicate" can actually take here.
        //
        // remember with no keys, matching draggableState's own remember below: the velocity
        // lambda closes over `density`, and the state (being remembered unkeyed) has always
        // held the lambda that captured the *first* composition's density. Keying these on
        // `density` would hand the release site a lambda capturing a newer density than the one
        // inside the state, which is a behavioral difference, not a fix — out of scope here.
        val positionalThreshold: (totalDistance: Float) -> Float =
            remember { { totalDistance -> totalDistance * 0.4f } }
        val velocityThreshold: () -> Float =
            remember { { with(density) { 200.dp.toPx() } } }

        val draggableState = remember {
            AnchoredDraggableState(
                initialValue = PlayerV2State.Collapsed,
                positionalThreshold = positionalThreshold,
                velocityThreshold = velocityThreshold,
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
        // Held as progressState (the State object itself, not just its value) so Stage 2.3's
        // reveal-clip shape below can be handed a reference and read .value from inside
        // createOutline — a draw/layout-phase read, not a composition-phase one. The `by`
        // delegate immediately below still unwraps it for every composition-phase use
        // (isContentPresent, the sheetHeight/cornerRadius/surfaceColor lerps), which is what
        // recomposes this subtree once per frame while a drag is in flight. That per-frame
        // recomposition is unchanged from before this stage; the design doc's fix (§5 —
        // lambda-based modifiers reading the transition in the draw phase) belongs with the
        // SeekableTransitionState rewrite, not here.
        val progressState = remember(availableHeightPx, collapsedHeightPx) {
            derivedStateOf {
                val offset = draggableState.offset
                if (offset.isNaN()) return@derivedStateOf 0f
                val collapsedAnchor = availableHeightPx - collapsedHeightPx
                if (collapsedAnchor <= 0f) 0f
                else ((collapsedAnchor - offset) / collapsedAnchor).coerceIn(0f, 1f)
            }
        }
        val progress by progressState

        // ── Migration Step 3: the transition, shadow-driven and not yet consumed ──────────
        //
        // docs/player-v2-migration-plan.md Step 3. Everything in this block is additive: the
        // gesture path above and every consumer below still run entirely off progressState.
        // Step 4 flips the consumers over; Step 8 deletes the AnchoredDraggableState that
        // currently feeds this.
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
        // Held as a State<Float> rather than unwrapped with `by`: this is the shape Step 4
        // needs (progressState is passed *as a State* into PlayerV2ExpandedContent and
        // PlayerV2MiniContent precisely so EnvelopeRevealShape and the mini fade can read it in
        // the draw phase), and it keeps the per-frame value out of this composable's
        // composition-phase reads in the meantime.
        val expansionState = transition.animateFloat(
            transitionSpec = { tween(PlayerV2TransitionMs, easing = LinearEasing) },
            label = "expansion",
        ) { state -> if (state == PlayerV2State.Expanded) 1f else 0f }

        // The shadow. Mirrors the AnchoredDraggableState's live position into the seekable
        // state continuously — through the release animation too, not just the finger — so
        // that by Step 4 the transition is already a faithful echo of the gesture and flipping
        // the consumers over is the only thing that changes.
        //
        // One collector, never two. Both seekTo and snapTo take SeekableTransitionState's
        // internal mutatorMutex (Transition.kt:560, :609), so running them from separate
        // coroutines would let one cancel the other mid-drag — the intermittent failure the
        // plan flags as Step 8's main risk. Sequencing them on a single coroutine makes that
        // unrepresentable here rather than merely unlikely.
        LaunchedEffect(seekableState, draggableState, progressState) {
            snapshotFlow { draggableState.settledValue to progressState.value }
                .collect { (settledValue, rawProgress) ->
                    // Clamp at the producer. seekTo hard-throws on an out-of-range fraction
                    // (requirePrecondition(fraction in 0f..1f), Transition.kt:604-606) rather
                    // than coercing, and AnchoredDraggableState's spring is free to overshoot
                    // past an anchor — so a release near 1f can genuinely produce >1f here.
                    val p = rawProgress.coerceIn(0f, 1f)
                    val settledExpansion =
                        if (settledValue == PlayerV2State.Expanded) 1f else 0f

                    if (p == settledExpansion) {
                        // At rest on an anchor. snapTo commits currentState *and* targetState
                        // to it, which is what lets the next drag seek away from the right
                        // place — without this the seekable state would sit forever at
                        // (Collapsed, Expanded, 1f) after an expand, and the next collapse
                        // drag would compute its direction from a stale currentState.
                        // Self-no-ops when already fully settled there (:555-559).
                        seekableState.snapTo(settledValue)
                    } else {
                        // Seek away from wherever the seekable state currently rests, toward
                        // the opposite value. Anchoring on currentState rather than on the
                        // drag's direction is what dodges the silent-no-op gotcha: seekTo
                        // returns without doing anything when currentState == targetState and
                        // no new target is named (:615-617), so passing "Expanded" while
                        // already settled Expanded would move nothing, with no error. The
                        // opposite state is never equal to currentState, so the first seek of
                        // every drag always names a real target.
                        val target =
                            if (seekableState.currentState == PlayerV2State.Collapsed) {
                                PlayerV2State.Expanded
                            } else {
                                PlayerV2State.Collapsed
                            }
                        // fraction is "how far from currentState toward target", so it is
                        // progress itself when expanding and its complement when collapsing.
                        // Both directions land expansion == p: lerp(0f, 1f, p) = p, and
                        // lerp(1f, 0f, 1f - p) = p.
                        val fraction =
                            if (target == PlayerV2State.Expanded) p else 1f - p
                        seekableState.seekTo(fraction.coerceIn(0f, 1f), target)
                    }

                    // TEMPORARY DEBUG — delete at Step 4, when progressState starts deriving
                    // from expansionState and any disagreement becomes visible on screen
                    // instead of silent. Worth its five lines specifically because Step 3 is
                    // the one step in the plan with no on-device check: every way this wiring
                    // can fail (a swallowed seekTo, a stale currentState, a broken
                    // fraction↔expansion mapping) fails by leaving expansion parked at a
                    // constant, which compiles, runs, and looks exactly like success until
                    // Step 4 makes it load-bearing and it reads as a Step 4 regression.
                    // Logged, never thrown: an assertion that kills the app mid-gesture would
                    // be a worse bug than the one it detects.
                    val expansion = expansionState.value
                    if (abs(expansion - p) > 0.01f) {
                        Log.w(
                            "PlayerV2Shadow",
                            "expansion=$expansion drifted from progress=$p " +
                                "(current=${seekableState.currentState} " +
                                "target=${seekableState.targetState} " +
                                "fraction=${seekableState.fraction})",
                        )
                    }
                }
        }
        // ── end Step 3 ───────────────────────────────────────────────────────────────────

        // Stage 2.3: composition-presence gate only — decides whether
        // PlayerV2ExpandedContent exists in the tree at all, not whether it's visually shown.
        // That's now the reveal clip's job (EnvelopeRevealShape, applied inside the
        // composable itself), driven off the same progressState. Keyed to progress > 0f
        // rather than Stage 2.2's `draggableState.targetValue == Expanded` — targetValue only
        // flips at the anchoredDraggable midpoint, which would leave nothing composed (and
        // therefore nothing for the clip to grow open) for the first ~40% of every expand
        // drag. progress > 0f instead begins composing on the drag's very first frame, so the
        // clip always has content to reveal from 0.
        val isContentPresent = progress > 0f

        // Publish progress outward so SynthXBottomNav can slide/shrink/fade in lockstep.
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
        // nav receives is one that actually rendered. LaunchedEffect(progress) would also be
        // correct-by-construction but is the wrong instrument here — it would spin up and
        // cancel a coroutine on every frame of the drag, and the callback would land a
        // dispatch later than the frame it describes, leaving the nav visibly one frame
        // behind the finger. SideEffect is synchronous with the commit and allocates nothing.
        SideEffect { onProgressChanged(progress) }

        // Complement of SynthXBottomNav's own collapse: the nav slides down by
        // BottomNavHeight * progress while this shrinks by BottomNavHeight * (1 - progress),
        // so the two edges stay in contact at every intermediate value instead of drifting.
        // Sharing the BottomNavHeight constant (rather than restating 71.dp) is what keeps
        // that true — see PlayerChromeMetrics.kt.
        val bottomInset = lerpDp(BottomNavHeight, 0.dp, progress)

        // The sheet's height at progress 1, captured here rather than read at the call site
        // below. PlayerV2ExpandedContent is a BoxScope extension (it needs .align to
        // bottom-anchor itself), and BoxWithConstraintsScope extends BoxScope — so inside the
        // sheet Box's content lambda there are two candidate implicit receivers and
        // `maxHeight` no longer resolves. Naming it once here is clearer than an explicit
        // qualified receiver at the call site, and gives the value a name that says which
        // height it is.
        val expandedSheetHeight = maxHeight

        // The three animated properties, all driven off the one progress term so they cannot
        // drift out of step with each other or with the finger.
        val sheetHeight = lerpDp(CollapsedPlayerHeight, expandedSheetHeight, progress)
        // 24dp on the top corners only, deliberately breaking the app's 2dp shapes.small
        // rule — the documented Apple-Music-style exception, re-confirmed in the design doc
        // (§8) rather than accidentally normalized.
        val cornerRadius = lerpDp(0.dp, 24.dp, progress)
        // Lerped rather than flat: with no content whatsoever, this shift is the only way to
        // read progress mid-drag at a glance, and it costs one line and one import.
        val surfaceColor = lerp(Surface, SurfaceVariant, progress)

        // Outer wrapper whose only job is to hold the inset, exactly as the old player does
        // (PlayerSheetSkeleton.kt:257). The padding shrinks the coordinate space the sheet is
        // bottom-aligned *within*, rather than being applied to the sheet itself — so the
        // sheet's own .align(BottomCenter) below lands it above the nav with no change to its
        // alignment or height logic. At progress = 0 the space stops BottomNavHeight short of
        // the bottom and the 64dp collapsed bar sits on top of the nav's upper edge; at
        // progress = 1 the inset is 0 and the sheet reclaims the full height the nav vacated.
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
                                // so a tap on a child button doesn't also expand the sheet. Still
                                // missing here — a known, deliberately deferred bug, not a
                                // regression: Stage 2.1 already made this reachable via the mini
                                // play/pause button, and Stage 2.2 has now added several more
                                // interactive children (seek bar, prev/next, 3-dot, queue) that
                                // hit the same gap. The fix is deferred until after Stage 2.4
                                // (album art shared-element morph), since this gesture detector
                                // changes shape again at that point and re-deriving the guard
                                // twice isn't worth it.
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
                                // Release, in two explicitly separated halves — target
                                // selection, then the animation that acts on it. This was one
                                // `draggableState.settle(velocity)` call before migration Step
                                // 2a (docs/player-v2-migration-plan.md) split it apart.
                                //
                                //   1. Target selection: the positional/velocity threshold
                                //      logic, ported verbatim from Foundation's own private
                                //      computeTarget in Step 2a. See computeReleaseTarget
                                //      below. Unchanged by Step 2b and still fed the real
                                //      release velocity — which anchor we go to is decided
                                //      exactly as it always was.
                                //   2. The animation: **Step 2b changes this line, and only
                                //      this line.** It was `animateToWithDecay(releaseTarget,
                                //      velocity)` — the call settle() made internally
                                //      (AnchoredDraggable.kt:1078), which projects the fling
                                //      with the exponentialDecay spec and only falls back to
                                //      the spring when the decay can't reach the target. It is
                                //      now the plain `animateTo(releaseTarget)`.
                                //
                                // Why: SeekableTransitionState.animateTo takes no velocity
                                // parameter at all (Step 0, Finding 1 — there is no overload
                                // that does), so the velocity-seeded decay-then-spring release
                                // has no equivalent on the other side of this migration. Rather
                                // than discover that at Step 7, where it would be tangled up
                                // with reversal continuity and the fraction-space spring, this
                                // step drops velocity seeding *alone*, on the otherwise
                                // untouched AnchoredDraggable path, so the only variable in the
                                // A/B is the one being measured.
                                //
                                // No animationSpec argument: animateTo's default resolves
                                // through usePreModifierChangeBehavior to the state's own
                                // snapAnimationSpec (AnchoredDraggable.kt:1370-1375), i.e.
                                // Motion.SpringStandard as configured above — the same spring
                                // animateToWithDecay used for its non-decay path. Passing it
                                // explicitly would just restate it.
                                //
                                // The release is genuinely velocity-free, not merely
                                // velocity-unseeded-at-the-call: animateTo internally starts
                                // from the state's `lastVelocity` (:1378), but this drag loop
                                // feeds the state through dispatchRawDelta, which calls
                                // dragTo(newOffset) at the default lastKnownVelocity = 0f
                                // (:1248, :684), so lastVelocity is 0f at release. `velocity`
                                // below survives only as computeReleaseTarget's input, which is
                                // why it is still read and still needed.
                                val velocity = velocityTracker.calculateVelocity().y
                                val releaseTarget = draggableState.anchors.computeReleaseTarget(
                                    currentOffset = draggableState.requireOffset(),
                                    velocity = velocity,
                                    positionalThreshold = positionalThreshold,
                                    velocityThreshold = velocityThreshold,
                                )
                                scope.launch {
                                    draggableState.animateTo(releaseTarget)
                                }
                            }
                        }
                    },
            ) {
                if (isContentPresent) {
                    PlayerV2ExpandedContent(
                        song = song,
                        // The sheet's *fully expanded* height, not the live sheetHeight — this
                        // is what makes the expanded layout static. See the composable's doc.
                        expandedHeight = expandedSheetHeight,
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
                        progress = progressState,
                        bottomInset = bottomInset,
                    )
                }
                PlayerV2MiniContent(
                    song = song,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onPlayPauseClick = onPlayPauseClick,
                    progress = progressState,
                )
            }
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
 * @param currentOffset the state's live offset, i.e. `AnchoredDraggableState.requireOffset()`.
 * @param velocity the release velocity in px/s, signed — positive is downward in this
 *   coordinate space.
 * @param positionalThreshold the *same lambda instance* the state was constructed with — see
 *   the hoist in [PlayerV2Host].
 * @param velocityThreshold likewise.
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
 * animated in place. The call site's presence gate (`progress > 0f` in [PlayerV2Host]) still
 * decides whether this composable exists in the tree at all; once present, this clip decides
 * how much of it is visible. The fact that this block renders underneath the mini content
 * whenever both are visible in the same frame is unrelated to the reveal and still expected —
 * z-order layering between the two is out of scope for this stage.
 *
 * Stage 2.3b makes two changes on top of that.
 *
 * First, the container is no longer `fillMaxSize`. It is [requiredHeight] at
 * [expandedHeight] — the sheet's fully expanded height, constant for the screen — and
 * bottom-anchored inside the sheet Box. Under `fillMaxSize` this Column was re-measured
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
 * @param expandedHeight the sheet's height at progress 1, i.e. [PlayerV2Host]'s
 *   `BoxWithConstraints.maxHeight`. Deliberately *not* the live `sheetHeight` — see above.
 * @param bottomInset [PlayerV2Host]'s own progress-driven sheet padding
 *   (`lerpDp(BottomNavHeight, 0.dp, progress)`), threaded in only so this container can cancel
 *   it back out of its own placement. It is `align(BottomCenter)`-welded to the sheet's bottom
 *   edge, and that edge itself descends as the padding on the sheet's parent Box relaxes from
 *   BottomNavHeight to 0dp across the drag — without cancellation the content would silently
 *   drift down by up to BottomNavHeight as the nav slides out from under the sheet. Mirrors
 *   PlayerSkeletonControlsOverlay.kt's identical `+ bottomInset` correction on the old player's
 *   equivalent bottom-anchored block. Reused, not recomputed — same instance [PlayerV2Host]
 *   already applies to its own outer Box padding.
 */
@Composable
private fun BoxScope.PlayerV2ExpandedContent(
    song: Song,
    expandedHeight: Dp,
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
    bottomInset: Dp,
) {
    // Keyed on progress's own identity (stable for this composable's whole lifetime — it's
    // the same derivedStateOf instance from PlayerV2Host on every recomposition), so this
    // Shape instance itself is built once, not once per frame.
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
            // requiredHeight, not height: the parent sheet Box is measured at the live
            // `sheetHeight`, so plain Modifier.height() — which is documented as a *preferred*
            // size and resolves as `constraints.constrain(target)` (Size.kt, enforceIncoming =
            // true) — would be clamped straight back down to sheetHeight and this whole change
            // would silently do nothing. requiredHeight passes enforceIncoming = false and so
            // ignores the incoming maximum, which is precisely what is wanted here.
            .requiredHeight(expandedHeight)
            // Bottom-anchored, so the container overhangs the shorter sheet upward and is
            // clipped by the sheet Box's own .clip() rather than being squashed.
            .align(Alignment.BottomCenter)
            // Cancels [bottomInset] back out of this container's placement — see the
            // parameter doc above. Positive Y, same sign as
            // PlayerSkeletonControlsOverlay.kt's `+ bottomInset` correction: this Column is
            // welded to the sheet's bottom edge, which itself descends as bottomInset
            // relaxes toward 0dp across the drag, so adding it back here holds the content
            // at a fixed screen position instead of drifting down with that edge. Ordered
            // after .align (whose BoxScope alignment data this doesn't affect) and before
            // .clip (clip is size-driven, not position-driven, so it clips the same shape
            // regardless of where this container is placed).
            .offset(y = bottomInset)
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
            PlayerAlbumArt(
                song = song,
                isPlaying = isPlaying,
                size = side,
                // The mini bar's 44dp thumbnail would visibly upscale at this size.
                fullResolution = true,
            )
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
 * @param progress the same `progressState` reference [PlayerV2Host] hands to
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
private fun BoxScope.PlayerV2MiniContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    progress: State<Float>,
) {
    // `DragResistantIconButton`'s `enabled` is a plain Boolean composable parameter, not a
    // draw-phase-readable modifier — unlike the graphicsLayer alpha below, there is no way to
    // gate it without a composition-phase read of `progress.value` somewhere. Wrapped in
    // derivedStateOf (mirroring this file's own `progressState`/`isContentPresent` pattern
    // above) rather than a bare `val`, so this composable only recomposes when the *boolean*
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
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(CollapsedPlayerHeight)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerAlbumArt(
            song = song,
            isPlaying = isPlaying,
            size = 44.dp,
        )
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
