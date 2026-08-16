package com.v1per.synthx.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/** Matches the old QueueHeader's art size — the queue-mode header is a relocation of that layout. */
private val QueueModeArtSize: Dp = 44.dp

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
internal fun QueueModeContent(
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
            state = queueListState,
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
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
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }

        MoreOptionsButton(song = song, enabled = enabled)
    }
}
