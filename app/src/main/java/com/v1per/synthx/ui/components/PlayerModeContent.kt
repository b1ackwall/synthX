package com.v1per.synthx.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Height Player mode holds back from the album art for the title/artist row and its trailing
 * gap (≈52dp row + 16dp spacer). Only used to cap the art on short screens — the art's real
 * size is width-derived, so without this cap a landscape or small-height device would size it
 * taller than the region containing it and push the title row out.
 */
private val TitleRowReservedHeight: Dp = 68.dp

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
internal fun PlayerModeContent(
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
        MoreOptionsButton(song = song, enabled = enabled)
    }
}

/**
 * Same [LocalSongActions] trigger in both header modes, just relocated per layout.
 *
 * Internal (not private) because both [NowPlayingTitleRow] here and QueueModeHeader in
 * PlayerQueueModeContent.kt use it — co-located with Player mode's header since that's its
 * original home, referenced across the package boundary by the other file.
 */
@Composable
internal fun MoreOptionsButton(song: Song, enabled: Boolean) {
    val songActions = LocalSongActions.current
    DragResistantIconButton(
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
