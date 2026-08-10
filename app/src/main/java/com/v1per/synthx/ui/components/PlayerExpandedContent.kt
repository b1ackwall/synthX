package com.v1per.synthx.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.theme.Spacing

/** How far the queue list has to scroll before the controls overlay hides. */
private val ControlsCollapseThreshold: Dp = 32.dp

/**
 * Fixed gap between the sheet's top edge and the mode content, identical in both modes.
 *
 * Sized to clear the two things PlayerSheet draws at the very top of the sheet: the 24dp
 * top corner radius (`RoundedCornerShape(topStart/topEnd = 24.dp)`) and the 1dp playback
 * hairline pinned to `Alignment.TopCenter`. 32dp gives 8dp of breathing room past the
 * corner curve, which is the binding constraint of the two.
 */
private val TopMargin: Dp = 32.dp

/**
 * Fixed footprint of the bottom controls overlay. Player mode reserves exactly this much
 * bottom padding so its content never renders underneath; Queue mode deliberately does not,
 * so its rows run behind the overlay and are revealed by occlusion when it fades out.
 *
 * Fixed floor of the overlay's own children:
 *   16 (top)  + 48 (seek block) + 32 (gap) + 96 (transport) + 48 (queue button) + 16 (bottom)
 *   = 256dp
 * The remaining ~44dp is absorbed by the single internal `weight(1f)` between the transport
 * row and the queue button. That weight resolves against this constant and nothing else —
 * which is the whole point of the overlay being fixed-height: its internal spacing can no
 * longer drift with what happens to sit above it.
 *
 * The 48dp seek block is only exact because PlayerSeekBar pins its timestamp row to a fixed
 * height (24 touch target + 8 + 16 labels); without that the fixed floor would grow with the
 * system font scale and could squeeze the weight(1f) to zero. Do not un-pin it.
 *
 * This value excludes the navigation-bar inset, which is added at the single call site below
 * (see `overlayHeight`) so the overlay's height, Player mode's bottom padding, and QueueList's
 * bottom contentPadding all come from one expression and cannot drift apart.
 */
private val ControlsOverlayHeight: Dp = 300.dp

/**
 * Full player, coordinating album art, seek bar, transport controls, and the Up Next queue
 * — the queue is a second content mode of this same surface (toggled by [showQueue]), not a
 * separate screen.
 *
 * Two layers, not one column:
 *  - **Layer 1** is the mode content, cross-faded in its entirety between Player and Queue.
 *    Player mode is constrained (bottom-padded by the overlay height); Queue mode is not, and
 *    runs to the sheet's true bottom edge underneath the overlay.
 *  - **Layer 2** is [PlayerControlsSection], a fixed-height bottom-aligned overlay drawn last
 *    and therefore topmost, rendered identically in both modes.
 *
 * The two layers share no weight pool, which is the fix for the spacing drift the old single
 * weighted Column had: there, the controls' own `weight()` spacers resolved against whatever
 * else happened to be in the Column, so the same source rendered ~53dp in Player mode and
 * ~193dp in Queue mode.
 */
@Composable
fun PlayerExpandedContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    alpha: Float,
    artSize: Dp,
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
    modifier: Modifier = Modifier,
) {
    val enabled = alpha > 0.5f
    var showQueue by remember { mutableStateOf(false) }

    // Hoisted (rather than left inside QueueList) so scroll position can drive
    // controlsVisible below — QueueList's own reorder/remove/shuffle-gating logic is
    // otherwise untouched.
    val queueListState = rememberLazyListState()
    val collapseThresholdPx = with(LocalDensity.current) { ControlsCollapseThreshold.toPx() }

    // Only meaningful in Queue mode: Player mode always shows the overlay (the `!showQueue ||`
    // below), so this doesn't need to reset on mode switch.
    val controlsVisible by remember {
        derivedStateOf {
            queueListState.firstVisibleItemIndex == 0 &&
                queueListState.firstVisibleItemScrollOffset < collapseThresholdPx
        }
    }

    // Single source of truth for the overlay's footprint. The sheet drops to the screen edge
    // when expanded (PlayerSheet's bottomInset lerps to 0), so the nav-bar inset has to be
    // folded in here or the queue button sits on top of the gesture bar. Everything that needs
    // to know how tall the overlay is reads this one value: the overlay's own height, Player
    // mode's bottom padding, and QueueList's bottom contentPadding.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val overlayHeight = ControlsOverlayHeight + navBarInset

    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            .statusBarsPadding(),
    ) {
        // ─── LAYER 1: mode content ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = TopMargin)
                .padding(horizontal = Spacing.HorizontalPadding),
        ) {
            Crossfade(targetState = showQueue, label = "playerContentMode") { queueMode ->
                if (!queueMode) {
                    PlayerModeContent(
                        song = song,
                        isPlaying = isPlaying,
                        enabled = enabled,
                        artSize = artSize,
                        bottomReserved = overlayHeight,
                    )
                } else {
                    QueueModeContent(
                        song = song,
                        isPlaying = isPlaying,
                        enabled = enabled,
                        upcomingQueue = upcomingQueue,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        onShuffleClick = onShuffleClick,
                        onRepeatClick = onRepeatClick,
                        onQueueSongClick = onQueueSongClick,
                        onQueueMove = onQueueMove,
                        onQueueRemove = onQueueRemove,
                        queueListState = queueListState,
                        bottomContentPadding = overlayHeight,
                    )
                }
            }
        }

        // ─── LAYER 2: controls overlay (topmost — composed last in this Box) ─────────
        PlayerControlsSection(
            controlsVisible = !showQueue || controlsVisible,
            currentPosition = currentPosition,
            duration = duration,
            onSeek = onSeek,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onPlayPauseClick = onPlayPauseClick,
            onPreviousClick = onPreviousClick,
            onNextClick = onNextClick,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            enabled = enabled,
            showQueue = showQueue,
            onQueueClick = { showQueue = !showQueue },
            bottomInset = navBarInset,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(overlayHeight)
                .padding(horizontal = Spacing.HorizontalPadding),
        )
    }
}
