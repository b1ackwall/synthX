package com.v1per.synthx.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.DragResistantIconButton
import com.v1per.synthx.ui.components.PlayerSeekBar
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Controls overlay: seek bar, transport controls, queue placeholder —
 * PlayerControlsSection.kt's Column, reproduced here (that composable is `internal` but
 * tightly coupled to PlayerExpandedContent's showQueue/controlsVisible state this skeleton
 * doesn't have yet). Queue-mode content (PlayerSkeletonQueueContent's QueueList) now sits
 * beneath this overlay in the same screen region, so the source file's no-op
 * `.clickable(indication = null, onClick = {})` touch-blocker is restored on the outer
 * Column, in the same position PlayerControlsSection.kt used it: opaque background alone
 * doesn't stop touches reaching the queue rows underneath — Compose hit-tests every
 * overlapping pointer input handler at a position, not just the topmost drawn one. Real
 * controls (seek bar, transport, queue button) still consume their own touches first —
 * child-before-parent dispatch — so this only ever swallows taps on the overlay's empty
 * background.
 *
 * [bottomInset] is PlayerSheetSkeleton's own progress-driven sheet padding, threaded in only so
 * this Column can cancel it back out of its own placement — see the offset's doc comment below
 * for why this is the one child of the sheet that compensates for it.
 *
 * [controlsVisible] is the queue-list scroll-position term computed in PlayerSheetSkeleton.kt
 * (mirrors the old (dead) PlayerExpandedContent.kt's identically-named value, same
 * ControlsCollapseThreshold and derivedStateOf against the shared queueListState). Combined
 * here as `!isQueueMode || controlsVisible` — the overlay always shows in player mode, and is
 * scroll-gated only once queue mode is entered — same logic the old code used for its own
 * `!showQueue || controlsVisible`.
 *
 * The result is animated via `animateFloatAsState` rather than wrapping this Column in
 * `AnimatedVisibility`: this file already expresses every other visibility term
 * ([expandedContentAlpha]) as a plain multiplied-in float via `.alpha()`, not as composition
 * enter/exit, and `overlayVisibleAlpha * expandedContentAlpha` composes with that existing
 * multiplier the same way [PlayerSkeletonQueueContent]'s `queueContentAlpha` already composes
 * two float terms together — keeping one visibility mechanism in this file rather than mixing
 * float-alpha and AnimatedVisibility's own composition-add/remove semantics, which would also
 * complicate the touch-shield gating below (an exiting `AnimatedVisibility` child stays
 * composed mid-animation, so a boolean gate on it wouldn't ever cleanly say "gone").
 */
@Composable
fun BoxScope.PlayerSkeletonControlsOverlay(
    height: Dp,
    bottomInset: Dp,
    surfaceColor: Color,
    expandedContentAlpha: Float,
    expandedContentEnabled: Boolean,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    isQueueMode: Boolean,
    controlsVisible: Boolean,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Player mode: always shown. Queue mode: scroll-gated by controlsVisible. Matches the old
    // PlayerExpandedContent.kt's `!showQueue || controlsVisible` exactly.
    val overlayVisible = !isQueueMode || controlsVisible
    // Fast, not Normal: this is scroll-driven chrome show/hide, not a content reveal — it
    // should track the queue-scroll gesture snappily rather than visibly lag behind it. Same
    // tween tokens the old PlayerControlsSection.kt used for its AnimatedVisibility fadeIn/
    // fadeOut.
    val overlayVisibleAlpha by animateFloatAsState(
        targetValue = if (overlayVisible) 1f else 0f,
        animationSpec = tween(Motion.DurationInstant, easing = Motion.EasingStandard),
        label = "controlsOverlayScrollVisibility",
    )

    // Gates every real control in this overlay (seek bar, transport, queue button) — not
    // just the background shield above. expandedContentEnabled alone only tracks the
    // collapse/expand axis; without overlayVisibleAlpha too, these controls' own gesture
    // detectors stayed fully live even while scrolled away and faded/gone in queue mode
    // (alpha is draw-only in Compose, it doesn't disable hit-testing), letting touches in
    // that region register as real seeks/clicks on invisible controls — the same class of
    // bug the shield fix addressed, but on real controls the shield's own gating can't
    // reach (they consume their own touches first, child-before-parent). Same 0.5f
    // threshold convention as the shield fix, for the same reason.
    val overlayControlsEnabled = expandedContentEnabled && overlayVisibleAlpha > 0.5f

    // Slide-up: the overlay rises [ContentSlideDistance] into its resting place as it fades
    // in, rather than materialising in position. Compose's +Y is downward, so the positive
    // start value genuinely puts it *below* where it comes to rest, and lerping toward 0dp
    // is genuine upward travel.
    //
    // [bottomInset] is then added on top as an equal-and-opposite correction, NOT as extra
    // travel. This Column is align(BottomCenter)-welded to the sheet's bottom edge, and that
    // edge is itself descending: PlayerSheetSkeleton pads the sheet's parent Box by the same
    // bottomInset, lerped BottomNavHeight→0dp across the drag, so the sheet can grow into the
    // strip the bottom nav vacates. Adding it back here cancels it out of this one element's
    // frame, collapsing the arithmetic to
    //
    //     overlayTop = maxHeight − bottomInset − height + slide + bottomInset
    //                = maxHeight − height + slide
    //
    // — resting position plus slide, with no progress-driven frame term left. Without the
    // correction the two motions very nearly annihilate: the fade window is the last 60% of
    // the drag, over which the padding releases 71dp × 0.6 ≈ 42.6dp downward against the
    // slide's 40dp upward, leaving ~2.6dp of net travel that reads on-device as a pure fade
    // with no slide at all — the confirmed root cause this correction exists for.
    //
    // This is the only child of the sheet that compensates for bottomInset, and deliberately
    // so: it is the only one whose own intended motion is the same order of magnitude as the
    // frame's. The player-mode title row travels ~271dp across the drag, which swamps 42.6dp;
    // queue mode's header/list only reach full opacity at progress ≈ 1, where bottomInset is
    // already 0dp; and the mini content and album art are top-anchored, so the release carries
    // them with the sheet rather than against them. Nothing changes at rest either way —
    // bottomInset is 0dp at progress 1, so the fully-expanded position is bit-identical to
    // what it was before this term existed.
    //
    // Known and accepted: because the block now sits `slide + bottomInset` lower than the
    // sheet's bottom edge mid-transition (up to ~82dp, versus ~40dp from the slide alone), the
    // sheet's own clip crops more of this Column's bottom while it rises — around 41dp at
    // half opacity, which partially crops the queue button. That is the intended
    // emerging-from-the-edge read, not a regression: the seek bar and transport controls sit
    // at the top of the block and are never affected.
    //
    // Driven by [expandedContentAlpha] alone — deliberately not the
    // `expandedContentAlpha * overlayVisibleAlpha` product the Column's own .alpha() uses:
    // overlayVisibleAlpha is the queue-list scroll-driven hide, and folding it in here would
    // make the overlay lurch 40dp downward and back every time the queue is scrolled past
    // (and away from) the collapse threshold. That hide stays a pure fade; this slide belongs
    // to the collapse/expand axis only.
    //
    // Bidirectional with no special-casing, because this is a pure function of a live,
    // continuously-recomputed value with no animation state of its own: dragging up raises
    // expandedContentAlpha 0→1 and the offset falls 40dp→0; dragging back down lowers it
    // 1→0 and the offset climbs 0→40dp, sliding the overlay back down and out. There is no
    // "opening" or "closing" branch to get wrong — only the sign of the lerp's endpoints.
    val overlaySlideOffset = lerpDp(ContentSlideDistance, 0.dp, expandedContentAlpha)

    Column(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .offset(y = overlaySlideOffset + bottomInset)
            .fillMaxWidth()
            .height(height)
            .alpha(expandedContentAlpha * overlayVisibleAlpha)
            .background(surfaceColor)
            // Only attached once the overlay is meaningfully visible/interactive — at low
            // progress this Column's fixed height overflows past the collapsed sheet's small
            // bounds and overlaps the mini bar's own screen region (Compose doesn't clip a
            // Box's children to its bounds by default), so an unconditional shield here would
            // consume mini-bar taps before they ever reach the sheet's own tap-to-expand
            // gesture detector — see that detector's down.isConsumed check in
            // PlayerSheetSkeleton.kt. Also gated on overlayVisibleAlpha (not the raw
            // overlayVisible boolean, and not just expandedContentEnabled): overlayVisible
            // flips the instant controlsVisible/isQueueMode change, same composition pass, but
            // overlayVisibleAlpha only reaches its new value DurationFast later via
            // animateFloatAsState — gating on the boolean instead of the alpha desyncs the
            // shield from what's actually on screen in both directions (confirmed): fading in,
            // the shield attaches before the overlay is visibly there, swallowing taps meant
            // for the QueueList rows still visible underneath; fading out, the shield detaches
            // before the overlay has visibly faded, leaking taps through a still-opaque
            // overlay. Reading overlayVisibleAlpha here instead ties the shield's presence to
            // the same continuous value the Column's own .alpha() below already renders from,
            // so hand-off matches the actual visual state at every frame of the fade, not just
            // its two discrete endpoints. `> 0.5f` mirrors this file's own
            // miniContentEnabled/expandedContentEnabled/queueContentEnabled convention
            // (PlayerSheetSkeleton.kt / PlayerSkeletonQueueContent.kt) for the identical
            // purpose — the halfway point of a fade is where a control's hit target and its
            // visual prominence both cross over, and reusing the existing threshold keeps one
            // convention for "is this alpha-faded element interactive yet" across the skeleton
            // rather than inventing a second one here.
            .then(
                if (expandedContentEnabled && overlayVisibleAlpha > 0.5f) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Spacing.HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        PlayerSeekBar(
            currentPosition = currentPosition,
            duration = duration,
            onSeek = onSeek,
            enabled = overlayControlsEnabled,
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previousEnabled = overlayControlsEnabled && hasPrevious
            val nextEnabled = overlayControlsEnabled && hasNext
            Spacer(modifier = Modifier.weight(2f))
            DragResistantIconButton(
                onClick = onPreviousClick,
                size = 72.dp,
                enabled = previousEnabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = stringResource(R.string.cd_previous_track),
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(if (previousEnabled) 1f else Alpha.Disabled),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            DragResistantIconButton(
                onClick = onPlayPauseClick,
                size = 96.dp,
                enabled = overlayControlsEnabled,
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
                enabled = nextEnabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = stringResource(R.string.cd_next_track),
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(if (nextEnabled) 1f else Alpha.Disabled),
                )
            }
            Spacer(modifier = Modifier.weight(2f))
        }

        // The overlay's only flexible gap — the remainder of ControlsOverlayHeight.
        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            // Toggles the queueProgress animation, reusing the exact Crossfade
            // timing/easing tokens the old PlayerExpandedContent.kt used for its
            // Player-mode/Queue-mode Crossfade. Same enabled gate now used by the
            // transport buttons and PlayerSeekBar (overlayControlsEnabled). onClick is the
            // remember-ed stable reference from PlayerSheetSkeleton, not an inline closure —
            // see its doc comment for why.
            DragResistantIconButton(
                onClick = onQueueClick,
                enabled = overlayControlsEnabled,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = stringResource(R.string.cd_queue),
                    tint = if (isQueueMode) TextPrimary else TextSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // Fixed bottom edge only — no navBarInset term, see ControlsOverlayHeight's
        // doc comment (in PlayerSheetSkeleton.kt) for why.
        Spacer(modifier = Modifier.height(Spacing.md))
    }
}
