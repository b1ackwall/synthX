package com.v1per.synthx.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BlobParams
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import com.v1per.synthx.ui.components.PlayerAlbumArt
import com.v1per.synthx.ui.components.PlayerSheetValue
import com.v1per.synthx.ui.components.animatedPlayerAlbumArtScale
import com.v1per.synthx.ui.components.extractArtworkSwatches
import com.v1per.synthx.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * Height reserved below the art for the regular player's title/artist row. Canonical
 * declaration used by PlayerSkeletonExpandedContent.kt (the title row's current home) and
 * the regular-player geometry in QueueArtPositioning.kt's computeArtPosition.
 *
 * Internal rather than private: `internal` in Kotlin is module-wide, not package-scoped, so
 * this is importable from ui/components/ despite the different package.
 */
internal val TitleRowReservedHeight: Dp = 52.dp

/**
 * Gap between the expanded sheet's top edge and the art region.
 */
private val ExpandedTopMargin: Dp = 32.dp

// Shared by the background color and its complementary album-art glow lightness.
private const val BaseSurfaceLightness = 0.1f

/**
 * Weight ratio (above : below) the art is split by within its region in expanded state —
 * the art sits slightly off-centre in that region, not dead-centered.
 */
private const val ArtWeightAbove = 1.5f
private const val ArtWeightBelow = 1f

/**
 * Fixed footprint of the bottom controls overlay (seek bar, transport controls, queue
 * placeholder). Used *without* adding a navigation-bar inset on top — PlayerSheetSkeleton
 * is hosted inside SynthXBottomNav's Box, which already applies `.navigationBarsPadding()`
 * around both the nav and the sheet, so the inset is already excluded from this composable's
 * own `maxHeight`. Re-adding it here would double-count it.
 */
private val ControlsOverlayHeight: Dp = 300.dp

/**
 * How far below its resting position an alpha-faded block starts before sliding up into
 * place. Both the controls overlay (PlayerSkeletonControlsOverlay) and queue mode's
 * header/list block (PlayerSkeletonQueueContent) travel this distance as their own alpha
 * term runs 0→1, so the two read as one motion rather than two independently-tuned ones.
 *
 * Internal rather than private for the same reason [TitleRowReservedHeight] above is —
 * top-level `private` in Kotlin is file-private, and both consumers live in other files.
 * Declared here, alongside the sheet's other layout constants, rather than duplicated as a
 * bare `40.dp` literal in each consumer.
 *
 * Deliberately not applied to the album art (its own mini↔expanded↔queue morph already owns
 * its position and size) or to the player-mode title row (whose slide is emergent from the
 * art/sheet geometry — see QueueArtPositioning.kt's titleRowY derivation).
 */
internal val ContentSlideDistance: Dp = 40.dp

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
    upcomingQueue: List<QueueSlot>,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onQueueSongClick: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    onQueueRemove: (songId: String) -> Unit,
    onProgressChanged: (Float) -> Unit = {},
    collapsePlayerSignal: Int = 0,
    // Whether the sheet is currently the visible/interactive one — false on routes outside
    // bottomBarRoutes (Settings, Library Management, etc.), where SynthXBottomNav.kt's
    // BottomChromeOverlay zero-sizes this composable via `modifier` instead of removing it
    // from composition, to keep draggableState/QueueModeState alive across the visit. Layout
    // size alone doesn't gate BackHandler below — its registration follows composition
    // presence, not measured bounds — so it needs this separate signal.
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current.applicationContext
    var blobs by remember {
        mutableStateOf(
            listOf(
                // Opacity and falloff are derived per blob from its own extracted lightness
                // on every song change below; only sizeFraction is fixed per slot.
                BlobParams(sizeFraction = 1.0f),
                BlobParams(sizeFraction = 0.8f),
                BlobParams(sizeFraction = 0.6f),
            ),
        )
    }
    var baseSurfaceColor by remember {
        mutableStateOf(Color.hsl(125f, 0.5f, BaseSurfaceLightness))
    }
    // Always three entries, including before artwork loads or when extraction fails.
    // Keep the same fallback tones as the player's existing blobs.
    var artworkSwatches by remember {
        mutableStateOf(blobs.map { ExtractedSwatchHsl(it.hue, it.saturation, it.lightness) })
    }
    val currentSongId by rememberUpdatedState(song.id)

    LaunchedEffect(song.id) {
        val uri = song.albumArtFullUri ?: song.albumArtUri ?: return@LaunchedEffect
        val swatches = extractArtworkSwatches(context, uri)
        if (swatches.size != 3) return@LaunchedEffect
        if (currentSongId != song.id) return@LaunchedEffect
        artworkSwatches = swatches
        blobs = blobs.mapIndexed { index, blob ->
            val swatch = swatches[index]
            // Additive (BlendMode.Plus) blending blows out light colours, so intensity
            // scales inversely with this blob's own extracted lightness.
            val derivedOpacity = (1f - swatch.lightness).coerceIn(0f, 1f)
            val derivedFalloff = (1f - swatch.lightness).coerceIn(0f, 0.9f)
            blob.copy(
                hue = swatch.hue,
                saturation = swatch.saturation,
                lightness = swatch.lightness,
                opacity = derivedOpacity,
                gradientFalloffFraction = derivedFalloff,
            )
        }
        // The base shares Blob 1's hue, with its own fixed saturation and lightness.
        baseSurfaceColor = Color.hsl(swatches[0].hue, 0.5f, BaseSurfaceLightness)
    }

    val scope = rememberCoroutineScope()
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }
    // Higher-than-default friction (stock exponentialDecay is 1.0f) lets a fling decelerate
    // before handing off to the snap spring, so a fast release reads as one continuous motion
    // rather than a coast that stops dead and is then yanked the rest of the way.
    val decayAnimationSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()

        val draggableState = remember {
            AnchoredDraggableState(
                initialValue = PlayerSheetValue.Collapsed,
                // 20% of the full collapsed↔expanded travel, not the 0.4f used by
                // RefinedBottomSheet/QueueRow. That convention is tuned for short sheets and
                // single-row swipes; here the travel is nearly the whole screen, so 0.4f meant
                // a slow, non-flick drag had to cover ~294dp before it would snap — far more
                // than a deliberate drag feels like it should need. Fast flicks are unaffected:
                // velocityThreshold below short-circuits the positional test entirely.
                positionalThreshold = { totalDistance -> totalDistance * 0.2f },
                velocityThreshold = { with(density) { 40.dp.toPx() } },
                snapAnimationSpec = Motion.SpringStandard,
                decayAnimationSpec = decayAnimationSpec,
            )
        }

        // `visible &&` guards against intercepting system back on a route where this sheet is
        // zero-sized/invisible (e.g. Settings) but was left Expanded before navigating away —
        // without it, back would silently collapse the hidden sheet instead of letting normal
        // navigation (e.g. popping out of Settings) happen. See `visible`'s doc comment above.
        BackHandler(enabled = visible && draggableState.currentValue == PlayerSheetValue.Expanded) {
            scope.launch { draggableState.animateTo(PlayerSheetValue.Collapsed) }
        }

        // Collapses the sheet on external request (e.g. "Go to Album"/"Go to Artist"
        // navigating away from underneath it) — same target as BackHandler above, just
        // triggered by a caller-supplied signal instead of the system back gesture. Guarded
        // on > 0 rather than reacting to every value change so the initial default (0) never
        // fires a spurious collapse-from-collapsed on first composition.
        LaunchedEffect(collapsePlayerSignal) {
            if (collapsePlayerSignal > 0) {
                draggableState.animateTo(PlayerSheetValue.Collapsed)
            }
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

        // Queue-mode toggle, its crossfade progress, the queue list's own scroll state, and
        // the derived controls-overlay visibility — all gathered behind one remembered holder
        // instead of half a dozen separate remember calls at this call site. See
        // QueueModeState.kt for the state itself and the two bug-fix rationales
        // (controlsVisible's scroll-correction gating, the shuffle scroll-reset race) it
        // preserves.
        val queueModeState = rememberQueueModeState(
            scope = scope,
            upcomingQueue = upcomingQueue,
            onShuffleClick = onShuffleClick,
        )

        // One animation drives both the controls and the queue mask, including reversals.
        // Player mode always shows controls; QueueModeState keeps its directional toggle.
        val overlayVisibleAlpha by animateFloatAsState(
            targetValue = if (!queueModeState.isQueueMode || queueModeState.controlsVisible) 1f else 0f,
            animationSpec = tween(Motion.DurationInstant, easing = Motion.EasingStandard),
            label = "controlsOverlayScrollVisibility",
        )

        val sheetHeight = lerpDp(CollapsedPlayerHeight, maxHeight, progress)
        val cornerRadius = lerpDp(0.dp, 24.dp, progress)
        val bottomInset = lerpDp(BottomNavHeight, 0.dp, progress)

        // Identical formulas to the old (dead) PlayerSheetContent.kt — not a fresh judgment
        // call, reused verbatim: mini content fades out over the first third of the drag,
        // expanded content fades in over the last 60%, leaving a small dead zone
        // (progress 0.33–0.4) where neither is visible.
        val miniContentAlpha = (1f - progress * 3f).coerceIn(0f, 1f)
        val expandedContentAlpha = ((progress - 0.4f) * (1f / 0.6f)).coerceIn(0f, 1f)
        val miniContentEnabled = miniContentAlpha > 0.5f
        val expandedContentEnabled = expandedContentAlpha > 0.5f

        // Caps ControlsOverlayHeight against maxHeight (the sheet's fixed ceiling, not the
        // live sheetHeight) so the cap only activates for genuinely short screens — on any
        // realistically-sized phone this stays flat at ControlsOverlayHeight, unaffected by
        // collapse/expand dragging.
        val actualOverlayHeight = min(
            ControlsOverlayHeight,
            (maxHeight - ExpandedTopMargin - TitleRowReservedHeight).coerceAtLeast(0.dp),
        )

        // Album art offset/size, the title row's Y, and the two queue-mode anchors
        // (artOffsetYQueue, queueListOffsetY) PlayerSkeletonQueueContent needs — see
        // QueueArtPositioning.kt for the full mini↔expanded↔queue derivation.
        val artPosition = computeArtPosition(
            progress = progress,
            queueProgressValue = queueModeState.queueProgress.value,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            sheetHeight = sheetHeight,
            titleRowReservedHeight = TitleRowReservedHeight,
            expandedTopMargin = ExpandedTopMargin,
            artWeightAbove = ArtWeightAbove,
            artWeightBelow = ArtWeightBelow,
            controlsOverlayHeight = actualOverlayHeight,
            density = density,
        )

        // The queue-mode header region's bottom edge — where QueueList begins — in the sheet
        // Box's own coordinate space, which is also the space the gesture detector below reads
        // `down.position` in (PlayerSkeletonQueueContent's root Box is a fillMaxSize child of
        // that same Box, and everything inside it is placed by Modifier.offset from its top
        // edge, so the two origins coincide).
        //
        // Read live through rememberUpdatedState rather than captured by value: the detector's
        // pointerInput is keyed on Unit, so its lambda never restarts and would otherwise hold
        // whatever this was on the very first composition forever. That would be numerically
        // harmless today — computeArtPosition derives queueListOffsetY from constants alone
        // (ExpandedTopMargin, QueueHeaderRowHeight, QueueModeArtSize, QueueHeaderRegionHeight),
        // with no `progress` or `queueProgressValue` term, so the boundary genuinely doesn't
        // move — but reading it live keeps computeArtPosition the single source of truth for
        // the boundary instead of re-deriving the same formula here, and keeps this correct if
        // that anchor ever gains a dynamic term.
        val queueListTopY by rememberUpdatedState(artPosition.queueListOffsetY)

        Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
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

                            // Queue mode only: this detector answers for the header region
                            // alone — the sheet's top edge down to where QueueList begins.
                            // Below that boundary sit the queue list and the controls overlay.
                            // The list already keeps its own drags (Foundation's scrollable
                            // claims the pointer the moment slop is crossed, whether or not it
                            // can still scroll, and nothing here bridges the two via
                            // nestedScroll), but the overlay's background shield consumes only
                            // the *down*, never the moves — the slop wait below ignores a
                            // consumed down and only bails on consumed moves — so a drag
                            // anywhere on that ~300dp band used to fall straight through to
                            // here and close the sheet out from under the queue. Returning
                            // leaves the gesture un-claimed rather than fighting for it:
                            // nothing is consumed, and awaitEachGesture parks its own loop on
                            // awaitAllPointersUp() until the finger lifts, so this detector
                            // can't re-enter partway through and pick the gesture back up.
                            //
                            // Upper-bound-only, deliberately not a band that would also exclude
                            // the strip above the header: queue mode survives a collapse
                            // (nothing resets it but the queue button itself), so this branch
                            // is live while the sheet is a CollapsedPlayerHeight mini bar,
                            // where every touch lands far above this boundary and tap/
                            // drag-to-expand therefore keeps working untouched. A banded test
                            // would carve a dead strip out of the mini bar's own top edge.
                            //
                            // Gated on queueProgress rather than the isQueueMode flag to match
                            // this file's miniContentEnabled/expandedContentEnabled (and
                            // PlayerSkeletonQueueContent's queueContentEnabled, and the
                            // overlay shield's own) `> 0.5f` convention: the restriction
                            // engages and releases in step with what is actually on screen
                            // through the queue crossfade, not on a frame-zero boolean flip.
                            if (queueModeState.queueProgress.value > 0.5f &&
                                down.position.y >= queueListTopY.toPx()
                            ) {
                                return@awaitEachGesture
                            }

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
                AmbientBlobBackground(
                    blobs = blobs,
                    baseSurfaceColor = baseSurfaceColor,
                    blurRadiusDp = 150f.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        // Escape the animated sheet's height constraint and bottom-anchor
                        // the overflow. The renderer always measures at full height, so
                        // dragging only changes the outer clip, never its animation clock.
                        .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                        .requiredHeight(this@BoxWithConstraints.maxHeight),
                )

                PlayerSkeletonMiniContent(
                    song = song,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onPlayPauseClick = onPlayPauseClick,
                    miniContentAlpha = miniContentAlpha,
                    miniContentEnabled = miniContentEnabled,
                    height = CollapsedPlayerHeight,
                )

                // Keep the layout footprint equal to the art throughout the morph. The larger
                // glow overflows symmetrically without moving the art or changing its size.
                Box(
                    modifier = Modifier
                        .offset(x = artPosition.artOffsetX, y = artPosition.artOffsetY)
                        .size(artPosition.finalArtSize),
                ) {
                    val artScale = animatedPlayerAlbumArtScale(isPlaying)
                    val glowLightness = (1f - BaseSurfaceLightness).coerceIn(0f, 1f)
                    val glowColor = Color.hsl(
                        artworkSwatches[0].hue,
                        1.0f,
                        glowLightness,
                        0.9f,
                    )
                    val glowSize = artPosition.finalArtSize + 2f.dp * 2
                    Box(
                        modifier = Modifier
                            // requiredSize preserves the spread even under the mini art's
                            // tight constraints; Compose centers the overflow automatically.
                            .requiredSize(glowSize)
                            .align(Alignment.Center)
                            .scale(artScale)
                            .blur(
                                radius = 5f.dp,
                                edgeTreatment = BlurredEdgeTreatment.Unbounded,
                            )
                            .background(color = glowColor, shape = MaterialTheme.shapes.small),
                    )
                    PlayerAlbumArt(
                        song = song,
                        isPlaying = isPlaying,
                        size = artPosition.finalArtSize,
                        fullResolution = true,
                        scale = artScale,
                    )
                }

                PlayerSkeletonExpandedContent(
                    song = song,
                    titleRowY = artPosition.titleRowY,
                    expandedContentAlpha = expandedContentAlpha,
                    queueProgressValue = queueModeState.queueProgress.value,
                    expandedContentEnabled = expandedContentEnabled,
                )

                PlayerSkeletonQueueContent(
                    song = song,
                    artOffsetYQueue = artPosition.artOffsetYQueue,
                    queueListOffsetY = artPosition.queueListOffsetY,
                    headerRegionHeight = QueueHeaderRegionHeight,
                    queueListState = queueModeState.queueListState,
                    queueScrollConnection = queueModeState.queueScrollConnection,
                    expandedContentAlpha = expandedContentAlpha,
                    queueProgressValue = queueModeState.queueProgress.value,
                    expandedContentEnabled = expandedContentEnabled,
                    queueModeArtSize = QueueModeArtSize,
                    upcomingQueue = upcomingQueue,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onShuffleClick = queueModeState.onShuffleClickWithScrollReset,
                    onRepeatClick = onRepeatClick,
                    onQueueSongClick = onQueueSongClick,
                    onQueueMove = onQueueMove,
                    onQueueRemove = onQueueRemove,
                    controlsOverlayHeight = actualOverlayHeight,
                    controlsOverlayAlpha = expandedContentAlpha * overlayVisibleAlpha,
                    bottomInset = bottomInset,
                )

                PlayerSkeletonControlsOverlay(
                    swatches = artworkSwatches,
                    height = actualOverlayHeight,
                    // Passed so the overlay can cancel this padding back out of its own
                    // placement — it is bottom-anchored to the very edge this inset moves.
                    bottomInset = bottomInset,
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
                    isQueueMode = queueModeState.isQueueMode,
                    overlayVisibleAlpha = overlayVisibleAlpha,
                    onQueueClick = queueModeState.onQueueClick,
                )
            }
        }
    }
}
