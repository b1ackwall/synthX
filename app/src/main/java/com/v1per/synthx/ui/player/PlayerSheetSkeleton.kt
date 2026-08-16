package com.v1per.synthx.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.PlayerAlbumArt
import com.v1per.synthx.ui.components.PlayerSheetValue
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SurfaceVariant
import kotlinx.coroutines.launch

/**
 * Height reserved below the art for the title/artist row, mirrored from
 * PlayerModeContent.kt's private TitleRowReservedHeight (68dp) — that constant isn't shared
 * across the package boundary, and the real title row hasn't been ported into the skeleton
 * yet. Duplicated literal, flagged for consolidation once PlayerModeContent's layout is
 * actually ported here.
 */
private val TitleRowReservedHeight: Dp = 68.dp

/**
 * Gap between the expanded sheet's top edge and the art region, mirrored from
 * PlayerExpandedContent.kt's private TopMargin (32dp) — same cross-package-boundary
 * duplication as [TitleRowReservedHeight] above, for the same reason.
 */
private val ExpandedTopMargin: Dp = 32.dp

/**
 * Weight ratio (above : below) the art is split by within its region in expanded state,
 * mirrored from PlayerModeContent.kt's Spacer(weight(1.5f)) / Spacer(weight(1f)) pair —
 * the art sits slightly off-centre in that region, not dead-centered.
 */
private const val ArtWeightAbove = 1.5f
private const val ArtWeightBelow = 1f

/**
 * Fixed footprint of the bottom controls overlay (seek bar, transport controls, queue
 * placeholder), mirrored from PlayerExpandedContent.kt's private ControlsOverlayHeight
 * (300dp). Unlike that source constant, this is used *without* adding a navigation-bar
 * inset on top — PlayerSheetSkeleton is hosted inside SynthXBottomNav's Box, which already
 * applies `.navigationBarsPadding()` around both the nav and the sheet, so the inset is
 * already excluded from this composable's own `maxHeight`. Re-adding it here (as the old,
 * differently-hosted PlayerExpandedContent does) would double-count it. Flagged as a
 * deliberate deviation, not an oversight.
 */
private val ControlsOverlayHeight: Dp = 300.dp

/**
 * Queue-mode header art size, mirrored from PlayerQueueModeContent.kt's private
 * QueueModeArtSize (44dp) — same cross-package-boundary duplication as the other mirrored
 * constants above.
 */
private val QueueModeArtSize: Dp = 44.dp

/**
 * Approximate height of the (not yet ported) queue-mode header row, used only to vertically
 * center the 44dp queue art within it. The old QueueModeHeader's height was content-driven
 * (sized by its tallest child — effectively the two-line title/artist column), not a fixed
 * constant, so there is no exact source value to mirror here. [TitleRowReservedHeight] (68dp)
 * is reused as the closest existing approximation already in this file for a two-line
 * title/artist block, rather than inventing a new number.
 */
private val QueueHeaderRowHeight: Dp = TitleRowReservedHeight

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
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    upcomingQueue: List<Song>,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onQueueSongClick: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
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

        // Queue-mode toggle, orthogonal to the collapse/expand `progress` axis above — driven
        // by the queue button tap, not the drag gesture. `isQueueMode` is the source of truth
        // for the animation's target; `queueProgress` is what the art/header math below
        // actually reads, so the two never disagree mid-animation the way inferring one from
        // the other's targetValue would.
        var isQueueMode by remember { mutableStateOf(false) }
        val queueProgress = remember { Animatable(0f) }

        // Stable-identity click handler, `remember`-ed once (no keys) rather than declared
        // inline at the button's call site. `dragResistantClickable`'s pointerInput is keyed
        // on (interactionSource, enabled, onClick) — an inline lambda there would get a new
        // identity every recomposition, and this composable recomposes every animation frame
        // while `progress` is mid-spring (see `progress`'s derivedStateOf above), which was
        // restarting the gesture coroutine repeatedly during a tap-triggered expand and
        // eating same-frame taps on the queue button (confirmed root cause). `remember`-ing
        // the lambda once is sufficient — it still reads `isQueueMode`/`queueProgress`/`scope`
        // through their stable, remembered delegate/object references at invocation time, not
        // via captured-by-value snapshots, so it always acts on current state despite being
        // constructed only once. Play/pause doesn't need this: `onPlayPauseClick` is already a
        // stable forwarded parameter reference, not a closure built inside this composable.
        val onQueueClick: () -> Unit = remember {
            {
                isQueueMode = !isQueueMode
                scope.launch {
                    queueProgress.animateTo(
                        if (isQueueMode) 1f else 0f,
                        animationSpec = tween(Motion.DurationNormal, easing = Motion.EasingStandard),
                    )
                }
                Unit
            }
        }

        val sheetHeight = lerpDp(CollapsedPlayerHeight, maxHeight, progress)
        val cornerRadius = lerpDp(0.dp, 24.dp, progress)
        val bottomInset = lerpDp(BottomNavHeight, 0.dp, progress)
        // No visible content, but a subtle structural-color shift confirms mini vs. expanded
        // state at a glance without touching the accent color or any real UI.
        val surfaceColor = lerp(Surface, SurfaceVariant, progress)

        // Identical formulas to the old (dead) PlayerSheetContent.kt — not a fresh judgment
        // call, reused verbatim: mini content fades out over the first third of the drag,
        // expanded content fades in over the last 60%, leaving a small dead zone
        // (progress 0.33–0.4) where neither is visible.
        val miniContentAlpha = (1f - progress * 3f).coerceIn(0f, 1f)
        val expandedContentAlpha = ((progress - 0.4f) * (1f / 0.6f)).coerceIn(0f, 1f)
        val miniContentEnabled = miniContentAlpha > 0.5f
        val expandedContentEnabled = expandedContentAlpha > 0.5f

        // Same lerp as the old PlayerSheet.kt:205 — 44dp mini thumbnail growing to the
        // expanded player's full-width art — capped the same way PlayerModeContent.kt:63-64
        // capped it, against available height minus the (not yet ported) title row's space.
        val artSize = lerpDp(44.dp, maxWidth - Spacing.HorizontalPadding * 4, progress)
        val artMaxHeight = (maxHeight - TitleRowReservedHeight - ControlsOverlayHeight).coerceAtLeast(0.dp)
        val constrainedArtSize = min(artSize, artMaxHeight)

        // Art position, lerped between its two known-correct endpoints so the morph is
        // continuous while matching each source layout exactly at progress 0 / 1:
        //  - Mini (progress 0): PlayerMiniBar.kt — leading element of a Spacing.md-padded
        //    Row, vertically centered against the row's own height.
        //  - Expanded (progress 1): PlayerModeContent.kt — horizontally centered; vertically
        //    split 1.5:1 (above:below) within the region below ExpandedTopMargin and above
        //    the reserved title-row space.
        val artOffsetXMini = Spacing.md
        val artOffsetXExpanded = (maxWidth - constrainedArtSize) / 2
        // Old QueueModeHeader's Row carries no horizontal padding of its own — its inset is
        // entirely the ancestor Layer 1 Box's `.padding(horizontal = Spacing.HorizontalPadding)`
        // in PlayerExpandedContent.kt, the same 16dp token this file already reuses as
        // ExpandedTopMargin's horizontal sibling below.
        val artOffsetXQueue = Spacing.HorizontalPadding
        val expandedTargetX = lerpDp(artOffsetXExpanded, artOffsetXQueue, queueProgress.value)
        val artOffsetX = lerpDp(artOffsetXMini, expandedTargetX, progress)

        val artOffsetYMini = (sheetHeight - constrainedArtSize) / 2
        val expandedRegionHeight =
            (sheetHeight - ExpandedTopMargin - TitleRowReservedHeight - ControlsOverlayHeight)
                .coerceAtLeast(0.dp)
        val expandedFlexSpace = (expandedRegionHeight - constrainedArtSize).coerceAtLeast(0.dp)
        val artOffsetYExpanded = ExpandedTopMargin +
            expandedFlexSpace * (ArtWeightAbove / (ArtWeightAbove + ArtWeightBelow))
        // Old PlayerExpandedContent.kt's Layer 1 Box uses `.padding(top = TopMargin)` where
        // TopMargin = 32dp — the exact same value already mirrored into this file as
        // ExpandedTopMargin, so the queue header's top anchor reuses that existing constant
        // rather than a new one. QueueModeArtSize is then centered within the
        // QueueHeaderRowHeight approximation declared above.
        val artOffsetYQueue = ExpandedTopMargin + (QueueHeaderRowHeight - QueueModeArtSize) / 2
        // Fixed resting position for the shuffle/repeat row + Up Next list, directly below
        // the queue header — see PlayerSkeletonQueueContent's doc comment for why this isn't
        // lerped like the art/header positions above it.
        val queueListOffsetY = artOffsetYQueue + QueueHeaderRowHeight
        val expandedTargetY = lerpDp(artOffsetYExpanded, artOffsetYQueue, queueProgress.value)
        val artOffsetY = lerpDp(artOffsetYMini, expandedTargetY, progress)

        val expandedTargetSize = lerpDp(constrainedArtSize, QueueModeArtSize, queueProgress.value)
        val finalArtSize = lerpDp(44.dp, expandedTargetSize, progress)

        // Title row sits right below the art, in the "below" share (weight 1 of the 1.5:1
        // split) of the same expandedFlexSpace the art's own Y offset above already
        // computes — same relationship as PlayerModeContent.kt's Spacer(weight(1f)) between
        // the art and NowPlayingTitleRow. Only meaningful once expandedContentAlpha fades
        // it in, so its mini-state endpoint (directly under the mini art) is never visible,
        // just a sane continuous starting point for the lerp.
        val titleRowYMini = artOffsetYMini + constrainedArtSize
        val titleRowYExpanded = artOffsetYExpanded + constrainedArtSize +
            expandedFlexSpace * (ArtWeightBelow / (ArtWeightAbove + ArtWeightBelow))
        val titleRowY = lerpDp(titleRowYMini, titleRowYExpanded, progress)

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
                            // Main pass propagates child-before-parent, so if a nested
                            // dragResistantClickable (e.g. the mini play/pause button) sits
                            // under this down, its own pointerInput has already run and
                            // consumed it by the time this line executes — down.consume() is
                            // the first thing dragResistantClickable's gesture loop does.
                            // Captured now, before the slop wait below (which itself consumes
                            // move events on every frame it runs, making isConsumed useless
                            // for this purpose from that point on).
                            //
                            // Deliberately not the "inspect the terminating up event"
                            // approach originally proposed: awaitVerticalTouchSlopOrCancellation
                            // returns null on both cancellation paths (consumed mid-drag, or
                            // genuine pointer-up) without exposing the terminating change on
                            // either — Foundation's internal loop swallows it. There is no
                            // event left to re-inspect afterward: a follow-up awaitPointerEvent()
                            // would block on the *next* gesture, not retroactively hand back
                            // the one that just ended. down.isConsumed sidesteps this by
                            // reading the one signal that's genuinely available up front.
                            val downConsumedByDescendant = down.isConsumed
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            var overSlopDelta = 0f
                            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                change.consume()
                                overSlopDelta = overSlop
                            }

                            if (drag == null) {
                                // Pointer lifted before crossing touch slop — a tap. Only
                                // expands from Collapsed; tapping while Expanded is a no-op —
                                // drag and system back (BackHandler, above) are the only ways
                                // to collapse. Skipped entirely if a descendant already
                                // claimed this down for its own click (e.g. mini play/pause) —
                                // otherwise every tap on a child button would also expand the
                                // sheet.
                                if (!downConsumedByDescendant &&
                                    draggableState.currentValue == PlayerSheetValue.Collapsed
                                ) {
                                    scope.launch { draggableState.animateTo(PlayerSheetValue.Expanded) }
                                }
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
            ) {
                PlayerSkeletonMiniContent(
                    song = song,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onPlayPauseClick = onPlayPauseClick,
                    miniContentAlpha = miniContentAlpha,
                    miniContentEnabled = miniContentEnabled,
                    height = CollapsedPlayerHeight,
                )

                // Positioned per the lerped artOffsetX/Y above. PlayerAlbumArt itself needs
                // no adaptation: it already takes exactly (song, isPlaying, size,
                // fullResolution) and owns its own real-art-vs-glitch-placeholder branch.
                Box(modifier = Modifier.offset(x = artOffsetX, y = artOffsetY)) {
                    PlayerAlbumArt(
                        song = song,
                        isPlaying = isPlaying,
                        size = finalArtSize,
                        fullResolution = true,
                    )
                }

                PlayerSkeletonExpandedContent(
                    song = song,
                    titleRowY = titleRowY,
                    expandedContentAlpha = expandedContentAlpha,
                    queueProgressValue = queueProgress.value,
                    expandedContentEnabled = expandedContentEnabled,
                )

                PlayerSkeletonQueueContent(
                    song = song,
                    artOffsetYQueue = artOffsetYQueue,
                    queueListOffsetY = queueListOffsetY,
                    surfaceColor = surfaceColor,
                    expandedContentAlpha = expandedContentAlpha,
                    queueProgressValue = queueProgress.value,
                    expandedContentEnabled = expandedContentEnabled,
                    queueModeArtSize = QueueModeArtSize,
                    upcomingQueue = upcomingQueue,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onShuffleClick = onShuffleClick,
                    onRepeatClick = onRepeatClick,
                    onQueueSongClick = onQueueSongClick,
                    onQueueMove = onQueueMove,
                    controlsOverlayHeight = ControlsOverlayHeight,
                )

                PlayerSkeletonControlsOverlay(
                    height = ControlsOverlayHeight,
                    surfaceColor = surfaceColor,
                    expandedContentAlpha = expandedContentAlpha,
                    expandedContentEnabled = expandedContentEnabled,
                    currentPosition = currentPosition,
                    duration = duration,
                    onSeek = onSeek,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    onPreviousClick = onPreviousClick,
                    onNextClick = onNextClick,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onPlayPauseClick = onPlayPauseClick,
                    isQueueMode = isQueueMode,
                    onQueueClick = onQueueClick,
                )
            }
        }
    }
}
