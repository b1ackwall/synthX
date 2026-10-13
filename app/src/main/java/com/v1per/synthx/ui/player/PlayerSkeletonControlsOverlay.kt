package com.v1per.synthx.ui.player

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.DragResistantIconButton
import com.v1per.synthx.ui.components.PlayerSeekBar
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Spacing

/**
 * Controls overlay: seek bar, transport controls, queue placeholder, laid out as a Column.
 * Queue-mode content (PlayerSkeletonQueueContent's QueueList) sits beneath this overlay in
 * the same screen region, so a no-op `.clickable(indication = null, onClick = {})`
 * touch-blocker on the outer Column keeps taps in the transparent controls area from
 * reaching the queue rows underneath. Real
 * controls (seek bar, transport, queue button) still consume their own touches first —
 * child-before-parent dispatch — so this only ever swallows taps on the overlay's empty
 * background.
 *
 * [bottomInset] is PlayerSheetSkeleton's own progress-driven sheet padding, threaded in only so
 * this Column can cancel it back out of its own placement — see the offset's doc comment below
 * for why this is the one child of the sheet that compensates for it.
 *
 * [overlayVisibleAlpha] is animated by PlayerSheetSkeleton from QueueModeState's directional
 * controlsVisible toggle. Player mode always shows controls. The queue opacity mask shares
 * this exact animation so it releases and hides rows in step with the controls.
 *
 * The shared value uses `animateFloatAsState` rather than wrapping this Column in
 * `AnimatedVisibility`: this file already expresses every other visibility term
 * ([expandedContentAlpha]) as a plain multiplied-in float via `.alpha()`, not as composition
 * enter/exit, and `overlayVisibleAlpha * expandedContentAlpha` composes with that existing
 * multiplier the same way [PlayerSkeletonQueueContent]'s `queueContentAlpha` already composes
 * two float terms together — keeping one visibility mechanism in this file rather than mixing
 * float-alpha and AnimatedVisibility's own composition-add/remove semantics, which would also
 * complicate the touch-shield gating below (an exiting `AnimatedVisibility` child stays
 * composed mid-animation, so a boolean gate on it wouldn't ever cleanly say "gone").
 *
 * The controls themselves (seek bar, transport row, queue button) are additionally wrapped in
 * a plain `if (overlayVisibleAlpha > 0f)` below, for the same reason
 * [PlayerSkeletonQueueContent] conditionally composes `QueueList` instead of just alpha-fading
 * it (see that composable's doc comment): an always-composed control stays hit-testable at
 * alpha 0, so a swipe landing in this overlay's screen region *after* it has fully faded out
 * and settled hidden was reaching these controls' own (disabled-but-still-attached)
 * `pointerInput` nodes first and never falling through to `QueueList` underneath, even though
 * the controls were invisible and provably non-interactive. This does not reintroduce the
 * `AnimatedVisibility` problem noted above — it is a plain conditional keyed directly off the
 * same continuously-animated `overlayVisibleAlpha` this file already reads, not a second,
 * independent enter/exit lifecycle, so it drops the controls out of composition only on the
 * exact frame the fade-out animation actually reaches 0 (already fully invisible, at rest) and
 * brings them back on the first frame the fade-in animation moves off 0 — never mid-fade, so
 * the animation itself is untouched. `overlayControlsEnabled` below still separately gates
 * interactivity at the existing >0.5f threshold while the controls are composed and fading, so
 * behavior during the transition is unchanged; this only changes what happens once it's
 * finished.
 */
@Composable
internal fun BoxScope.PlayerSkeletonControlsOverlay(
    height: Dp,
    bottomInset: Dp,
    expandedContentAlpha: Float,
    expandedContentEnabled: Boolean,
    slideOffset: Dp,
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
    overlayVisibleAlpha: Float,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val transportGap = 40f.dp
    val contentColors = LocalPlayerContentColors.current ?: PlayerContentColors.Light

    // Slide-up: [slideOffset] is how far below its resting place the overlay sits, computed by
    // PlayerSheetSkeleton and shared with the queue mask. It is the sheet's drag offset measured
    // from the expanded anchor (PlayerSheetMotion.kt's controlsOverlaySlide), so the overlay
    // travels with the finger rather than materialising in position. Compose's +Y is downward,
    // so a positive value genuinely puts it *below* where it comes to rest.
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
    // — resting position plus slide, with no progress-driven frame term left, so the overlay
    // moves by exactly the slide and nothing else.
    //
    // This is the only child of the sheet that compensates for bottomInset: the mini content
    // and album art are top-anchored, so the release carries them with the sheet rather than
    // against them. Nothing changes at rest either way — bottomInset is 0dp at progress 1.
    //
    // The block sits `slide + bottomInset` lower than the sheet's bottom edge mid-transition,
    // so the sheet's own clip crops this Column from the bottom as it travels: the queue
    // button first, the seek bar last.
    //
    // The slide never includes overlayVisibleAlpha — deliberately not the
    // `expandedContentAlpha * overlayVisibleAlpha` product the Column's own .alpha() uses:
    // overlayVisibleAlpha is the queue-list scroll-driven hide, and that hide stays a pure
    // fade; this slide belongs to the collapse/expand axis only.
    //
    // Bidirectional with no special-casing, because this is a pure function of the sheet's
    // live drag offset with no animation state of its own.

    Column(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .offset(y = slideOffset + bottomInset)
            .fillMaxWidth()
            .height(height)
            .alpha(expandedContentAlpha * overlayVisibleAlpha)
            // Only attached once the overlay is meaningfully visible/interactive — at low
            // progress this Column's fixed height overflows past the collapsed sheet's small
            // bounds and overlaps the mini bar's own screen region (Compose doesn't clip a
            // Box's children to its bounds by default), so an unconditional shield here would
            // consume mini-bar taps before they ever reach the sheet's own tap-to-expand
            // gesture detector — see that detector's down.isConsumed check in
            // PlayerSheetSkeleton.kt. Also gated on overlayVisibleAlpha (not the raw
            // overlayVisible boolean, and not just expandedContentEnabled): overlayVisible
            // flips the instant controlsVisible/isQueueMode change, same composition pass, but
            // overlayVisibleAlpha only reaches its new value DurationInstant later via
            // animateFloatAsState — gating on the boolean instead of the alpha desyncs the
            // shield from what's actually on screen in both directions (confirmed): fading in,
            // the shield attaches before the overlay is visibly there, swallowing taps meant
            // for the QueueList rows still visible underneath; fading out, the shield detaches
            // before the overlay has visibly faded, leaking taps through a still-visible
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
        // See this file's top doc comment for why this is gated on the animated
        // overlayVisibleAlpha itself rather than the raw overlayVisible boolean or a second
        // AnimatedVisibility: it only drops these pointerInput-bearing controls out of
        // composition once the fade-out has actually finished (alpha at rest at 0), never
        // mid-fade, so this is invisible to the animation and only closes the dead-swipe
        // window past that point.
        if (overlayVisibleAlpha > 0f) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            PlayerSeekBar(
                currentPosition = currentPosition,
                duration = duration,
                onSeek = onSeek,
                unplayedColor = contentColors.content.copy(alpha = Alpha.SeekTrackUnplayed),
                playedColor = contentColors.content.copy(alpha = Alpha.SeekTrackPlayed),
                enabled = overlayControlsEnabled,
            )
            Spacer(modifier = Modifier.height(transportGap))
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
                        tint = contentColors.content,
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
                            color = contentColors.content,
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
                            tint = contentColors.content,
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
                        tint = contentColors.content,
                        modifier = Modifier
                            .size(56.dp)
                            .alpha(if (nextEnabled) 1f else Alpha.Disabled),
                    )
                }
                Spacer(modifier = Modifier.weight(2f))
            }

            Spacer(modifier = Modifier.height(transportGap))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                // Toggles the queueProgress animation that crossfades between player mode
                // and queue mode. Same enabled gate now used by the
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
                        tint = if (isQueueMode) contentColors.content else contentColors.off,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // Fills the remaining height; 44dp remains at 300dp height with 40dp gaps.
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
