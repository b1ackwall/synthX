package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/** Full player, coordinating album art, seek bar, transport controls, and queue entry point. */
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
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same sheet SongRow opens; the player is never playlist-scoped so the id is null.
    val songActions = LocalSongActions.current
    val enabled = alpha > 0.5f

    Column(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha)
            .statusBarsPadding()
            .padding(horizontal = Spacing.HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Flexible top
        Spacer(modifier = Modifier.weight(1.5f))

        PlayerAlbumArt(
            song = song,
            isPlaying = isPlaying,
            size = artSize,
            fullResolution = true,
        )

        // Flexible between art and title
        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
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
            IconButton(
                onClick = { songActions.show(song, null) },
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

        Spacer(modifier = Modifier.height(Spacing.md))

        PlayerSeekBar(currentPosition = currentPosition, duration = duration, onSeek = onSeek)

        // Flexible between seek and controls
        Spacer(modifier = Modifier.weight(1f))

        PlayerTransportControls(
            isPlaying = isPlaying,
            onPlayPauseClick = onPlayPauseClick,
            onPreviousClick = onPreviousClick,
            onNextClick = onNextClick,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            enabled = enabled,
        )

        // Flexible between controls and queue
        Spacer(modifier = Modifier.weight(1f))

        // Bottom row — queue placeholder
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
                    tint = TextSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // Flexible breathing room below bottom icons section
        Spacer(modifier = Modifier.weight(0.5f))

        // Fixed bottom edge
        Spacer(modifier = Modifier.height(Spacing.md))
    }
}
