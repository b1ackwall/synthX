package com.v1per.synthx.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
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
import com.v1per.synthx.ui.components.DragResistantIconButton
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Queue-mode header: minimal text-only stand-in for PlayerQueueModeContent.kt's
 * QueueModeHeader — title/artist bound to real, live song data; the trailing button is a
 * visual placeholder only (a true no-op, not the real LocalSongActions-wired
 * MoreOptionsButton — that composable has no onClick override hook, so reusing it here would
 * also wire the real action, which is out of scope). Positioned at the same artOffsetYQueue
 * anchor the art animates toward; a leading spacer reserves the art's own footprint (same
 * technique the mini content Row uses), since the art itself is the separately positioned
 * shared element. Queue list / shuffle-repeat row are deliberately not built here — future
 * feature.
 */
@Composable
fun PlayerSkeletonQueueContent(
    song: Song,
    artOffsetYQueue: Dp,
    expandedContentAlpha: Float,
    queueProgressValue: Float,
    expandedContentEnabled: Boolean,
    queueModeArtSize: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .offset(y = artOffsetYQueue)
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding)
            .alpha(expandedContentAlpha * queueProgressValue),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(queueModeArtSize))
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
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
                )
            }
            DragResistantIconButton(
                onClick = {},
                enabled = expandedContentEnabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.cd_more_options),
                    tint = TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
