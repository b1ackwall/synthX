package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

/**
 * @param currentPlaylistId set only by PlaylistDetailScreen, so the actions sheet can
 *   offer "Remove from Playlist" in place of "Add to Playlist".
 */
@Composable
internal fun SongRow(
    song: Song,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    currentPlaylistId: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Pulled from composition rather than passed down: five screens use SongRow and none
    // of them need to know the sheet exists. See SongActionsHost.
    val songActions = LocalSongActions.current

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .then(
                    if (song.isMissing) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = ripple(color = Accent),
                            onClick = onClick,
                        )
                    }
                )
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Neon playing indicator bar — a missing song is never marked playing
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        if (isCurrentlyPlaying && !song.isMissing) Accent else Color.Transparent
                    ),
            )

            // Content dims when the song's file is missing; indicator bar stays outside
            Row(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (song.isMissing) Alpha.Disabled else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongIdentity(
                    song = song,
                    isCurrentlyPlaying = isCurrentlyPlaying,
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = { songActions.show(song, currentPlaylistId, SongActionSource.Default) },
                    // Matches the alpha dimming above and the omitted row-tap clickable below:
                    // previously this stayed fully clickable/focusable despite being visually
                    // dimmed to Alpha.Disabled — a real TalkBack mismatch (announced as enabled,
                    // looked disabled). The actions sheet is reachable another way for a missing
                    // song's own management (delete/etc.) via the Issues screen's dedicated menu.
                    enabled = !song.isMissing,
                    modifier = Modifier.padding(end = Spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = TextTertiary,
                    )
                }
            }
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}
