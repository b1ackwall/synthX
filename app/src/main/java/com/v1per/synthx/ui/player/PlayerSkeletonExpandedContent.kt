package com.v1per.synthx.ui.player

import android.annotation.SuppressLint
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tracing.Trace
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.perf.PerfTraceSections
import com.v1per.synthx.ui.components.MoreOptionsButton
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing

/**
 * Expanded content: title/artist row plus [MoreOptionsButton] (shared player-chrome
 * composable — internal, same Gradle module — reused as-is since it already wires
 * LocalSongActions correctly). Fades against queueProgressValue rather than just
 * expandedContentAlpha alone, so the two crossfade against each other on a queue-button
 * tap, not only across the collapse/expand drag.
 */
// Body is bracketed by begin/endSection (PerfTraceSections); try/finally cannot wrap composables.
@SuppressLint("UnclosedTrace")
@Composable
fun PlayerSkeletonExpandedContent(
    song: Song,
    titleRowY: Dp,
    expandedContentAlpha: Float,
    queueProgressValue: Float,
    expandedContentEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Trace.beginSection(PerfTraceSections.TitleRow)
    val contentColors = LocalPlayerContentColors.current ?: PlayerContentColors.Light
    Box(
        modifier = modifier
            .offset(y = titleRowY)
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding)
            .alpha(expandedContentAlpha * (1f - queueProgressValue)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = Motion.MarqueeIterations),
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = Motion.MarqueeIterations),
                )
            }
            MoreOptionsButton(song = song, enabled = expandedContentEnabled)
        }
    }
    Trace.endSection()
}
