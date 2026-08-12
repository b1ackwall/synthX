package com.v1per.synthx.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.launch

/**
 * Height of the collapsed mini-player bar. Exposed so MainScaffold can reserve the
 * matching bottom inset on scrollable content behind the sheet.
 */
val CollapsedPlayerHeight: Dp = 64.dp

/**
 * Footprint of the bottom nav (70dp NavigationBar + 1dp divider) the collapsed sheet clears.
 * Public so BottomChromeOverlay's nav slide-offset and MainScaffold's content padding share
 * this exact value instead of duplicating the magic number — any drift between them shows up
 * as a visible seam or overlap between the mini player and the nav.
 */
val BottomNavHeight: Dp = 71.dp

enum class PlayerSheetValue { Collapsed, Expanded }

/**
 * A single draggable player surface that morphs between a 64dp mini player (Collapsed)
 * and a full-screen player (Expanded). Every visual property lerps off one [progress]
 * float derived from an [AnchoredDraggableState] — there is no swap between two
 * composables, no shared-element transition.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerSheet(
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
    onQueueSongClick: (index: Int) -> Unit,
    onQueueMove: (from: Int, to: Int) -> Unit,
    onQueueRemove: (index: Int) -> Unit,
    onProgressChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }
    // Higher friction lets the fling decelerate before handing off to the snap spring.
    val decayAnimationSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()

        val draggableState = remember {
            AnchoredDraggableState(
                initialValue = PlayerSheetValue.Collapsed,
                positionalThreshold = { totalDistance -> totalDistance * 0.4f },
                velocityThreshold = { with(density) { 200.dp.toPx() } },
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                decayAnimationSpec = decayAnimationSpec,
            )
        }

        // While expanded, back collapses the sheet instead of falling through to the
        // NavHost's own back handling (which would otherwise navigate away). Consumes the
        // press entirely; disabled (and inert) whenever the sheet is collapsed.
        BackHandler(enabled = draggableState.currentValue == PlayerSheetValue.Expanded) {
            scope.launch { draggableState.animateTo(PlayerSheetValue.Collapsed) }
        }

        // Anchors depend on the measured height, which is only known inside BoxWithConstraints.
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

        // 0f = fully collapsed, 1f = fully expanded.
        val progress by remember(availableHeightPx, collapsedHeightPx) {
            derivedStateOf {
                val offset = draggableState.offset
                if (offset.isNaN()) return@derivedStateOf 0f
                val collapsedAnchor = availableHeightPx - collapsedHeightPx
                if (collapsedAnchor <= 0f) 0f
                else ((collapsedAnchor - offset) / collapsedAnchor).coerceIn(0f, 1f)
            }
        }

        // Report progress upward so chrome (e.g. the bottom nav) can slide with expansion.
        // Called directly in the composable body rather than via LaunchedEffect(progress):
        // progress changes every drag frame, and relaunching a coroutine just to make one
        // synchronous callback per frame is needless overhead.
        onProgressChanged(progress)

        PlayerSheetContent(
            song = song,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentPosition = currentPosition,
            duration = duration,
            progress = progress,
            fullWidth = maxWidth,
            fullHeight = maxHeight,
            onPlayPauseClick = onPlayPauseClick,
            onSeek = onSeek,
            onPreviousClick = onPreviousClick,
            onNextClick = onNextClick,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            upcomingQueue = upcomingQueue,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            onShuffleClick = onShuffleClick,
            onRepeatClick = onRepeatClick,
            onQueueSongClick = onQueueSongClick,
            onQueueMove = onQueueMove,
            onQueueRemove = onQueueRemove,
            onExpand = { scope.launch { draggableState.animateTo(PlayerSheetValue.Expanded) } },
            sheetModifier = Modifier.anchoredDraggable(draggableState, Orientation.Vertical),
        )
    }
}

@Composable
private fun PlayerSheetContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    progress: Float,
    fullWidth: Dp,
    fullHeight: Dp,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit = {},
    onPreviousClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    upcomingQueue: List<Song> = emptyList(),
    shuffleEnabled: Boolean = false,
    repeatMode: RepeatMode = RepeatMode.Off,
    onShuffleClick: () -> Unit = {},
    onRepeatClick: () -> Unit = {},
    onQueueSongClick: (index: Int) -> Unit = {},
    onQueueMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onQueueRemove: (index: Int) -> Unit = {},
    onExpand: () -> Unit,
    sheetModifier: Modifier = Modifier,
) {
    val sheetHeight = lerp(CollapsedPlayerHeight, fullHeight, progress)
    val artSize = lerp(44.dp, fullWidth - Spacing.HorizontalPadding * 4, progress)
    val cornerRadius = lerp(0.dp, 24.dp, progress) // flat when collapsed, rounded top when expanded
    val miniPlayerAlpha = (1f - progress * 3f).coerceIn(0f, 1f) // fades out over the first third
    val fullPlayerAlpha = ((progress - 0.4f) * (1f / 0.6f)).coerceIn(0f, 1f) // fades in after 40%
    // Collapsed, the sheet stops above the bottom nav; expanded, it drops to the screen
    // edge to cover the nav area (the nav itself slides away in MainScaffold). This inset
    // is driven by the internal progress only, so it never feeds back into the drag anchors.
    val bottomInset = lerp(BottomNavHeight, 0.dp, progress)
    val playbackProgress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)) {
        Box(
            modifier = sheetModifier
                .fillMaxWidth()
                .height(sheetHeight)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                .background(Surface),
        ) {
            // Top hairline doubles as a playback progress indicator — elapsed portion in
            // high-contrast accent, remaining portion at the dim structural Hairline value.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter),
            ) {
                if (playbackProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(playbackProgress)
                            .fillMaxHeight()
                            .background(Accent.copy(alpha = 0.65f)),
                    )
                }
                if (playbackProgress < 1f) {
                    Box(
                        modifier = Modifier
                            .weight(1f - playbackProgress)
                            .fillMaxHeight()
                            .background(Hairline),
                    )
                }
            }

            PlayerMiniBar(
                song = song,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                alpha = miniPlayerAlpha,
                onPlayPauseClick = onPlayPauseClick,
                onExpand = onExpand,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            PlayerExpandedContent(
                song = song,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                currentPosition = currentPosition,
                duration = duration,
                alpha = fullPlayerAlpha,
                artSize = artSize,
                onPlayPauseClick = onPlayPauseClick,
                onSeek = onSeek,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                upcomingQueue = upcomingQueue,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                onShuffleClick = onShuffleClick,
                onRepeatClick = onRepeatClick,
                onQueueSongClick = onQueueSongClick,
                onQueueMove = onQueueMove,
                onQueueRemove = onQueueRemove,
                // Built once at the sheet's *full* expanded height and pinned to the bottom,
                // rather than fillMaxSize()-ing inside this Box as it grows. The drag then
                // reveals pre-measured content from the bottom up through the sheet's clip
                // boundary instead of re-measuring the whole player every frame — and the
                // fixed-height controls overlay inside never has to negotiate a container
                // shorter than itself (which, mid-drag, it otherwise would).
                modifier = Modifier
                    .height(fullHeight)
                    .align(Alignment.BottomCenter),
            )
        }
    }
}

private val PreviewSong = Song(
    id = "preview_1",
    title = "Neon Pulse",
    artist = "v1per",
    album = "Ultraviolet",
    duration = 243_000L,
    filePath = "",
    albumArtUri = null,
)

@Preview(name = "PlayerSheet — Collapsed", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerSheetCollapsedPreview() {
    SynthXTheme {
        Box(modifier = Modifier.size(width = 400.dp, height = 800.dp)) {
            PlayerSheetContent(
                song = PreviewSong,
                isPlaying = false,
                isBuffering = false,
                currentPosition = 63_000L,
                duration = 243_000L,
                progress = 0f,
                fullWidth = 400.dp,
                fullHeight = 800.dp,
                onPlayPauseClick = {},
                onExpand = {},
            )
        }
    }
}

@Preview(name = "PlayerSheet — Expanded", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerSheetExpandedPreview() {
    SynthXTheme {
        Box(modifier = Modifier.size(width = 400.dp, height = 800.dp)) {
            PlayerSheetContent(
                song = PreviewSong,
                isPlaying = true,
                isBuffering = false,
                currentPosition = 63_000L,
                duration = 243_000L,
                progress = 1f,
                fullWidth = 400.dp,
                fullHeight = 800.dp,
                onPlayPauseClick = {},
                onExpand = {},
            )
        }
    }
}
