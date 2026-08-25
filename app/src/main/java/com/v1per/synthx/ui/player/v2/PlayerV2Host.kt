package com.v1per.synthx.ui.player.v2

import androidx.compose.animation.core.exponentialDecay
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * This was originally a blank dark surface that morphs between a 64dp mini bar and a
 * full-height sheet and nothing else. Stage 2.1 added real mini-bar content (art,
 * title/artist, play/pause) — see [PlayerV2MiniContent]. Stage 2.2 adds real expanded
 * content — see [PlayerV2ExpandedContent] — statically laid out at fixed final positions:
 * no drag-progress-driven offsets and no reveal-clip yet (both Stage 2.3), and no album art
 * frame yet (Stage 2.4), so the title block sits at a placeholder fixed top offset rather
 * than below real art bounds.
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerV2Host(
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

        // Stage 2.2: gates the expanded content's presence. Discrete, not a continuous fade —
        // targetValue flips the instant the drag crosses the anchoredDraggable midpoint
        // (whichever anchor .offset is currently closest to), same threshold that already
        // decides where settle() lands on release. A continuous progress-driven crossfade is
        // Stage 2.3's reveal-clip mechanism, not this stage's.
        val isExpanded = draggableState.targetValue == PlayerV2State.Expanded

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
                                // Release. settle() applies the positional threshold (40% of the
                                // travel) and the 200dp/s velocity threshold configured above,
                                // seeded with the real release velocity, so a short flick still
                                // completes and a slow release falls back to nearest-state.
                                val velocity = velocityTracker.calculateVelocity().y
                                scope.launch { draggableState.settle(velocity) }
                            }
                        }
                    },
            ) {
                if (isExpanded) {
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
                    )
                }
                PlayerV2MiniContent(
                    song = song,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onPlayPauseClick = onPlayPauseClick,
                )
            }
        }
    }
}

/**
 * Placeholder fixed offset for the title block's top edge, standing in for "below the album
 * art frame" until Stage 2.4 defines that frame's real bounds and size.
 */
private val ExpandedTitleTopOffset = 320.dp

/**
 * Stage 2.2: real expanded content — title/artist + 3-dot menu near the top, seek bar +
 * transport + queue button near the bottom. Statically laid out via a fillMaxSize Column
 * with a flexible middle spacer, not the old (detached) PlayerSkeletonControlsOverlay's
 * progress-driven slide/fade/reveal-clip wrapper — only its *inner* content (the seek bar,
 * transport row, and queue row themselves) is mirrored here. Rendered only while the call
 * site's `isExpanded` gate (`draggableState.targetValue == PlayerV2State.Expanded`) is true —
 * there is no fade-in/out of this block yet, so it appears/disappears abruptly at the drag's
 * midpoint. That abruptness, and the fact that this block currently renders underneath the
 * mini content whenever both happen to be visible in the same frame, are both expected at
 * this stage and fixed by Stage 2.3's reveal-clip mechanism, not addressed here.
 *
 * The queue button's [onClick][DragResistantIconButton] is a no-op: queue mode doesn't exist
 * until Stage 4, so there is nothing to wire it to yet, but it's laid out in its final
 * position now so Stage 4 only has to attach behavior, not insert a new element.
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(ExpandedTitleTopOffset))
        PlayerV2TitleRow(song = song)
        Spacer(modifier = Modifier.weight(1f))
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
            .padding(horizontal = Spacing.HorizontalPadding)
            .padding(start = Spacing.lg, end = 12.dp),
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
 * Stage 2.1: real mini-bar content — art, title/artist, play/pause. Deliberately static:
 * no fade-as-you-drag (design doc §4) and no shared-element art morph (§3) yet, both later
 * stages. Pinned to the sheet's own top edge at a fixed [CollapsedPlayerHeight] like the old
 * (detached) PlayerSkeletonMiniContent, but with no reveal/fade treatment it stays visible at
 * every progress value, including fully expanded — acceptable for now only because the
 * expanded state itself is still blank.
 */
@Composable
private fun BoxScope.PlayerV2MiniContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
) {
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
        DragResistantIconButton(onClick = onPlayPauseClick) {
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
