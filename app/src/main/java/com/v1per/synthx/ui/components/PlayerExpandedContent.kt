package com.v1per.synthx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/** Matches the old QueueHeader's art size — the queue-mode header is a relocation of that layout. */
private val QueueModeArtSize: Dp = 44.dp

/** How far the queue list has to scroll before the seek bar / transport controls hide. */
private val ControlsCollapseThreshold: Dp = 32.dp

/**
 * Full player, coordinating album art, seek bar, transport controls, and the Up Next queue
 * — the queue is a second content mode of this same surface (toggled by [showQueue]), not a
 * separate screen. Tapping the queue button cross-fades the header region between the
 * Now-Playing identity (large art + title/artist) and the queue's own header (small art +
 * title/artist + the queue list); everything else — this composable's own alpha-driven
 * reveal, the sheet's drag/collapse/back handling in [PlayerSheet] — is untouched by mode.
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

    // Only meaningful in Queue mode: Player mode always shows the seek bar/transport
    // controls (the `!showQueue ||` below), so this doesn't need to reset on mode switch.
    val controlsVisible by remember {
        derivedStateOf {
            queueListState.firstVisibleItemIndex == 0 &&
                queueListState.firstVisibleItemScrollOffset < collapseThresholdPx
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha)
            .statusBarsPadding()
            .padding(horizontal = Spacing.HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Player mode's own flexible top/art-title spacing — direct weighted children of
        // this Column, exactly as they were pre-merge. These can't live inside Crossfade
        // below: its content lambda isn't a ColumnScope, so weight() isn't available to
        // anything nested inside it — that's what let a single shared weight leak into
        // Player mode's spacing during the merge. Restoring literal weight-based spacing
        // means these two modes need to be independent direct children here, not both
        // routed through one Crossfade-owned allocation.
        if (!showQueue) {
            Spacer(modifier = Modifier.weight(1.5f))
        }

        // Only the "identity" content that actually needs to cross-fade lives in here, and
        // it's intentionally unweighted (wraps its own content height) in both branches —
        // Player mode's art keeps its old unconstrained natural size (no risk of being
        // squeezed into a too-small box), and Queue mode's small header no longer claims a
        // large fixed weight share it doesn't need, which is what starved QueueList below.
        Crossfade(
            targetState = showQueue,
            modifier = Modifier.fillMaxWidth(),
            label = "playerHeaderMode",
        ) { queueMode ->
            if (!queueMode) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PlayerAlbumArt(song = song, isPlaying = isPlaying, size = artSize, fullResolution = true)
                }
            } else {
                QueueModeHeader(
                    song = song,
                    isPlaying = isPlaying,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (!showQueue) {
            Spacer(modifier = Modifier.weight(1f))
            NowPlayingTitleRow(song = song, enabled = enabled)
        }

        if (showQueue) {
            QueuePlaybackModeRow(
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                onShuffleClick = onShuffleClick,
                onRepeatClick = onRepeatClick,
                modifier = Modifier.fillMaxWidth(),
            )
            QueueList(
                queue = upcomingQueue,
                shuffleEnabled = shuffleEnabled,
                onSongClick = onQueueSongClick,
                onMove = onQueueMove,
                onRemove = onQueueRemove,
                state = queueListState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

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
        )
    }
}

/**
 * Seek bar, transport controls, and the queue-toggle icon row — identical in Player mode and
 * Queue mode by construction, since both branches call this single composable rather than
 * maintaining their own copies. [controlsVisible] drives the seek-bar/transport
 * AnimatedVisibility (Player mode passes true always; Queue mode passes it based on scroll
 * position), but the spacing values themselves never vary by mode.
 *
 * `ColumnScope` receiver (not a wrapping Column) is deliberate: the weighted Spacers here must
 * negotiate space against the outer Column's other weighted children (the art/title spacers in
 * Player mode, QueueList's weight in Queue mode) in a single shared weight pool. Wrapping them
 * in a nested Column would isolate their weight() calls to that inner Column instead, breaking
 * the layout.
 */
@Composable
private fun ColumnScope.PlayerControlsSection(
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
) {
    AnimatedVisibility(
        visible = controlsVisible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
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
        }
    }

    // Flexible between controls and queue button — unconditional, so it (along with
    // QueueList's own weight above) absorbs the space AnimatedVisibility frees up
    // when it collapses.
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

    // Flexible breathing room below bottom icons section
    Spacer(modifier = Modifier.weight(0.5f))

    // Fixed bottom edge
    Spacer(modifier = Modifier.height(Spacing.md))
}

/**
 * Player mode's title/artist/3-dots row — split out from the album art (which lives inside
 * the header Crossfade) so it can sit as a direct child of the outer Column and use its
 * original weight-based spacing again. Content is byte-for-byte what NowPlayingHeader used
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
