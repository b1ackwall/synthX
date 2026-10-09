package com.v1per.synthx.ui.player

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
// DEAD CODE: the title color no longer branches on the build type. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.BuildConfig
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.BackgroundBrightness
import com.v1per.synthx.ui.components.MoreOptionsButton
import com.v1per.synthx.ui.theme.Spacing

/**
 * Expanded content: title/artist row plus [MoreOptionsButton] (shared player-chrome
 * composable — internal, same Gradle module — reused as-is since it already wires
 * LocalSongActions correctly). Fades against queueProgressValue rather than just
 * expandedContentAlpha alone, so the two crossfade against each other on a queue-button
 * tap, not only across the collapse/expand drag.
 */
@Composable
fun PlayerSkeletonExpandedContent(
    song: Song,
    titleRowY: Dp,
    expandedContentAlpha: Float,
    queueProgressValue: Float,
    expandedContentEnabled: Boolean,
    modifier: Modifier = Modifier,
    backgroundBrightness: BackgroundBrightness? = null,
) {
    // Same locked values in every build.
    val darkText = useDarkPlayerTitle(
        enabled = PlayerAltStageStyle.AdaptiveTextEnabled,
        brightness = backgroundBrightness,
        metric = PlayerAltStageStyle.LockedAdaptiveTextMetric,
        statistic = PlayerAltStageStyle.LockedAdaptiveTextStatistic,
        threshold = PlayerAltStageStyle.AdaptiveTextThreshold,
    )
    // DEAD CODE: the adaptive title color no longer reads the debug state. Delete in a cleanup pass. Ask  before deleting.
    // val debugState = PlayerBackgroundDebugStateHolder.state.value
    // enabled = effectivePlayerAdaptiveTextEnabled(BuildConfig.DEBUG, debugState),
    // metric = effectivePlayerAdaptiveTextMetric(BuildConfig.DEBUG, debugState),
    // statistic = effectivePlayerAdaptiveTextStatistic(BuildConfig.DEBUG, debugState),
    // threshold = effectivePlayerAdaptiveTextThreshold(BuildConfig.DEBUG, debugState),
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
                    color = playerTitleColor(darkText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = playerArtistColor(darkText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
            }
            MoreOptionsButton(song = song, enabled = expandedContentEnabled)
        }
    }
}
