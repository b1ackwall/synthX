package com.v1per.synthx.ui.player.v2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SurfaceVariant

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
 * **Migration complete as of Step 8.** The sheet is a `SeekableTransitionState` inside a
 * `SharedTransitionLayout`, drag deltas feed `seekTo` and releases feed `animateTo`, and
 * `AnchoredDraggableState` is gone entirely. Only `DraggableAnchors` survives, as a plain
 * immutable lookup table for [computeReleaseTarget]; see `releaseAnchors` for why.
 *
 * **This file split (post-migration).** The mechanism — the `SeekableTransitionState`, the
 * `Transition`, the gesture channel and its consumer, the release-target inputs — now lives in
 * [PlayerV2HostState] / [rememberPlayerV2HostState], and the tap/drag detector now lives in
 * [Modifier.playerV2DragGestures] in `PlayerV2Mechanism.kt`. This file was 925 lines, over the
 * project's 500-line limit, with all of that inside one composable body; the split is a pure
 * relocation with no behavior change — see the moved-to files' docs for the specific risk points
 * (pointerInput's key, remember's keys, `expansion`'s live-read) that had to be preserved exactly.
 *
 * Remaining deliberate divergences, all still scoped:
 * - No BackHandler, and no tap-to-collapse. Dragging down is currently the only way back to
 *   Collapsed.
 * - No `down.isConsumed` guard on the tap branch, so a tap on a child button also expands the
 *   sheet. Deferred out of Step 8 on purpose, and now a one-line follow-up in
 *   [Modifier.playerV2DragGestures].
 * - The album art is still statically positioned; the shared-element morph is Stage 2.4b, which
 *   the [SharedTransitionScope] receiver and both `AnimatedVisibilityScope`s now exist to make
 *   a pure addition.
 *
 * Stage 1.2 has since added the nav coordination that this list previously called out as
 * missing (§5): [onProgressChanged] reports the live drag progress outward, and the sheet
 * applies the matching bottom inset so the collapsed bar stacks above the nav rather than
 * overlaying it. Everything else above still stands.
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

    // BoxWithConstraints, not fillMaxSize alone: the expanded height target has to be a real
    // measured number (maxHeight) rather than a guess, and the drag anchors need the same
    // number in pixels (constraints.maxHeight) to place the Collapsed anchor. Mirrors how
    // PlayerSheetSkeleton derives both.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()
        val state = rememberPlayerV2HostState(availableHeightPx, density)
        val expansion = state.expansion

        // Publish expansion outward so SynthXBottomNav can slide/shrink/fade in lockstep.
        // SideEffect, not a bare call in the composition body — see PlayerV2HostState's history
        // for why a write-during-composition here would be wrong; this runs only after a
        // composition successfully commits.
        SideEffect { onProgressChanged(expansion) }

        // Complement of SynthXBottomNav's own collapse: the nav slides down by
        // BottomNavHeight * expansion while this shrinks by BottomNavHeight * (1 - expansion),
        // so the two edges stay in contact at every intermediate value instead of drifting.
        val bottomInset = lerpDp(BottomNavHeight, 0.dp, expansion)

        // The sheet's height at expansion 1, captured here rather than read at the call site
        // below. Deliberately *not* the live sheetHeight — see [PlayerV2ExpandedContent].
        val expandedSheetHeight = maxHeight

        // The three animated properties, all driven off the one expansion term so they cannot
        // drift out of step with each other or with the finger.
        val sheetHeight = lerpDp(CollapsedPlayerHeight, expandedSheetHeight, expansion)
        // 24dp on the top corners only, deliberately breaking the app's 2dp shapes.small
        // rule — the documented Apple-Music-style exception (design doc §8).
        val cornerRadius = lerpDp(0.dp, 24.dp, expansion)
        // Lerped rather than flat: with no content whatsoever, this shift is the only way to
        // read expansion mid-drag at a glance.
        val surfaceColor = lerp(Surface, SurfaceVariant, expansion)

        // Outer wrapper whose only job is to hold the inset, exactly as the old player does
        // (PlayerSheetSkeleton.kt:257). At expansion = 0 the space stops BottomNavHeight short
        // of the bottom and the 64dp collapsed bar sits on top of the nav's upper edge; at
        // expansion = 1 the inset is 0 and the sheet reclaims the full height the nav vacated.
        Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                    .background(surfaceColor)
                    // One unified detector arbitrating tap vs. drag itself — see
                    // [Modifier.playerV2DragGestures]'s doc for why it is keyed on
                    // `state.collapsedAnchorPx` rather than `state`.
                    .playerV2DragGestures(state),
            ) {
                // Migration Step 5: the expanded content's presence is the transition's job now.
                state.transition.AnimatedVisibility(
                    visible = { it == PlayerV2State.Expanded },
                    // The placement/sizing half of what used to be PlayerV2ExpandedContent's own
                    // modifier chain — see that composable's doc for why it had to move and why
                    // the split is size-neutral.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // The sheet's *fully expanded* height, not the live sheetHeight — this is
                        // what makes the expanded layout static. requiredHeight (not height) so
                        // the shorter sheet's incoming maximum is ignored and the container
                        // overhangs upward, to be clipped by the sheet Box's own .clip().
                        .requiredHeight(expandedSheetHeight)
                        // Cancels bottomInset back out of this container's placement — see
                        // [motionFrameOffsetY]'s doc for why this must be motion, not structure.
                        .motionFrameOffsetY(bottomInset),
                    // Nothing. The reveal clip is what makes this content appear (design doc §2).
                    enter = EnterTransition.None,
                    // ExitTransition.None would dispose the content before EnvelopeRevealShape
                    // can clip over it (Step 0's Finding 3); fadeOut(targetAlpha = 1f) is a hold,
                    // not a fade — confirmed inert from source, see PlayerV2Mechanism's
                    // PlayerV2TransitionMs doc for why the duration must match exactly.
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
                        progress = state.expansionState,
                    )
                }
            }
        }

        // Migration Step 6's AnimatedVisibility; Stage 2.4b-2 relocated it here, as a sibling of
        // the sheet Box above rather than a child of it — see PlayerV2Mechanism's history for why
        // (the live-resize / shared-element totalDurationNanos interaction).
        //
        // Position: `BottomCenter` + a **fixed** `-BottomNavHeight`, not the live `bottomInset` —
        // reproduces the mini bar's resting position and holds it there for the row's entire
        // visible span, rather than tracking bottomInset's drift toward 0.
        state.transition.AnimatedVisibility(
            visible = { it == PlayerV2State.Collapsed },
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = -BottomNavHeight),
            enter = EnterTransition.None,
            // The plan's "same inert-exit treatment" — a hold, not a fade. Governs only how long
            // the node survives after the state flips; the visible fade is
            // [PlayerV2MiniContent]'s own graphicsLayer alpha curve, deliberately untouched here.
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
                progress = state.expansionState,
            )
        }
    }
}
