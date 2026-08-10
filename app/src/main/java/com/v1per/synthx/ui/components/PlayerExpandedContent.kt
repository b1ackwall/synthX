package com.v1per.synthx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/** Matches the old QueueHeader's art size — the queue-mode header is a relocation of that layout. */
private val QueueModeArtSize: Dp = 44.dp

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
 * Height Player mode holds back from the album art for the title/artist row and its trailing
 * gap (≈52dp row + 16dp spacer). Only used to cap the art on short screens — the art's real
 * size is width-derived, so without this cap a landscape or small-height device would size it
 * taller than the region containing it and push the title row out.
 */
private val TitleRowReservedHeight: Dp = 68.dp

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

/**
 * Player mode's content, constrained to the region above the controls overlay by
 * [bottomReserved] — it must never render underneath it.
 *
 * [BoxWithConstraints] exists purely to cap the album art: [artSize] is derived from the
 * sheet's *width*, so on a short or landscape screen it can exceed the height available here.
 * Capping it to `maxHeight - TitleRowReservedHeight` makes the art shrink to fit instead of
 * silently overflowing or squeezing the title row to zero.
 */
@Composable
private fun PlayerModeContent(
    song: Song,
    isPlaying: Boolean,
    enabled: Boolean,
    artSize: Dp,
    bottomReserved: Dp,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomReserved),
    ) {
        val artMaxHeight = (maxHeight - TitleRowReservedHeight).coerceAtLeast(0.dp)
        val constrainedArtSize = min(artSize, artMaxHeight)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1.5 : 1 — the art sits slightly above the vertical centre of this region, as it
            // always has. These weights are now local to this branch and compete with nothing
            // outside it.
            Spacer(modifier = Modifier.weight(1.5f))
            PlayerAlbumArt(
                song = song,
                isPlaying = isPlaying,
                size = constrainedArtSize,
                fullResolution = true,
            )
            Spacer(modifier = Modifier.weight(1f))
            NowPlayingTitleRow(song = song, enabled = enabled)
            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

/**
 * Queue mode's content — deliberately *unconstrained*: it fills to the sheet's true bottom
 * edge and runs behind the controls overlay's footprint. Rows are occluded by the overlay
 * while it's visible and revealed when it fades out, purely by z-order; there is no
 * show/hide logic on the list itself.
 *
 * [bottomContentPadding] is what keeps that honest — without it the last few rows would sit
 * inside the viewport but permanently behind the overlay, with no scroll left to bring them
 * clear. It is a constant, never toggled on [controlsVisible]: changing contentPadding
 * changes the max-scroll offset, which would jerk the list mid-gesture and feed back into the
 * very scroll position that drives controlsVisible.
 */
@Composable
private fun QueueModeContent(
    song: Song,
    isPlaying: Boolean,
    enabled: Boolean,
    upcomingQueue: List<Song>,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onQueueSongClick: (index: Int) -> Unit,
    onQueueMove: (from: Int, to: Int) -> Unit,
    onQueueRemove: (index: Int) -> Unit,
    queueListState: LazyListState,
    bottomContentPadding: Dp,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        QueueModeHeader(
            song = song,
            isPlaying = isPlaying,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        QueuePlaybackModeRow(
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            onShuffleClick = onShuffleClick,
            onRepeatClick = onRepeatClick,
            modifier = Modifier.fillMaxWidth(),
        )
        QueueList(
            queue = upcomingQueue,
            onSongClick = onQueueSongClick,
            onMove = onQueueMove,
            onRemove = onQueueRemove,
            state = queueListState,
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * Seek bar, transport controls, and the queue-toggle icon row — the whole bottom overlay,
 * identical in Player mode and Queue mode by construction.
 *
 * [controlsVisible] fades the **entire** overlay, queue button included (Player mode passes
 * true always; Queue mode passes it based on scroll position). Once the queue is scrolled and
 * the overlay is gone, returning to Player mode means swiping the queue back down to reveal
 * the overlay and then tapping the queue button — the same round trip Apple Music makes you
 * do, and the reason the button is inside the fade rather than pinned outside it.
 *
 * [modifier] carries the fixed height from the call site. Everything below negotiates space
 * inside that fixed box only — the `weight(1f)` here is the ~44dp remainder of
 * [ControlsOverlayHeight] after its fixed children, and cannot be influenced by the mode
 * content on the layer beneath.
 */
@Composable
private fun PlayerControlsSection(
    controlsVisible: Boolean,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    enabled: Boolean,
    showQueue: Boolean,
    onQueueClick: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = controlsVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Surface)
                // Opaque background alone doesn't stop touches from reaching the queue rows
                // beneath — Compose hit-tests every overlapping pointer input handler at a
                // position, not just the topmost drawn one. This no-op clickable (no ripple,
                // no callback) consumes the down event so it never propagates to QueueRow.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(Spacing.md))
            PlayerSeekBar(currentPosition = currentPosition, duration = duration, onSeek = onSeek)
            Spacer(modifier = Modifier.height(Spacing.xl))
            PlayerTransportControls(
                isPlaying = isPlaying,
                onPlayPauseClick = onPlayPauseClick,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                enabled = enabled,
            )

            // The overlay's only flexible gap — the remainder of ControlsOverlayHeight.
            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = onQueueClick,
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = stringResource(R.string.cd_queue),
                        tint = if (showQueue) TextPrimary else TextSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // Fixed bottom edge + the gesture-nav clearance folded in from the call site.
            Spacer(modifier = Modifier.height(Spacing.md + bottomInset))
        }
    }
}

/**
 * Player mode's title/artist/3-dots row. Content is byte-for-byte what NowPlayingHeader used
 * to render here; only its container changed.
 */
@Composable
private fun NowPlayingTitleRow(
    song: Song,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = 12.dp), // 12dp intentional: Spacing.xs (4dp) + 8dp added
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
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MoreOptionsButton(song = song, enabled = enabled)
    }
}

/**
 * Small art (top-left) + title/artist beside it + the same 3-dots menu (top-right) —
 * Queue mode's header, relocated from the old QueueHeader.kt (same 44dp art size, same
 * text styles). No close button: dismissing the queue is the same queue-button tap or
 * sheet-collapse gesture as ever, there's no separate "screen" to close any more.
 */
@Composable
private fun QueueModeHeader(
    song: Song,
    isPlaying: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerAlbumArt(song = song, isPlaying = isPlaying, size = QueueModeArtSize)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.md),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        MoreOptionsButton(song = song, enabled = enabled)
    }
}

/** Same [LocalSongActions] trigger in both header modes, just relocated per layout. */
@Composable
private fun MoreOptionsButton(song: Song, enabled: Boolean) {
    val songActions = LocalSongActions.current
    IconButton(
        onClick = { songActions.show(song, null, SongActionSource.Player) },
        enabled = enabled,
    ) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.cd_more_options),
            tint = TextSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
}
