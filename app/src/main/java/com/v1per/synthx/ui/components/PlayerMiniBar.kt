package com.v1per.synthx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/** Collapsed mini player row — tap anywhere (but the button) to expand. */
@Composable
fun PlayerMiniBar(
    song: Song,
    isPlaying: Boolean,
    alpha: Float,
    onPlayPauseClick: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CollapsedPlayerHeight)
            .alpha(alpha)
            .clickable(enabled = alpha > 0.5f, onClick = onExpand)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerAlbumArt(song = song, isPlaying = isPlaying, size = 44.dp)
        Spacer(modifier = Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onPlayPauseClick,
            enabled = alpha > 0.5f,
        ) {
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
