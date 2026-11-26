package com.v1per.synthx.ui.screens.library

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.ArtPlaceholder
import com.v1per.synthx.ui.components.ArtPlaceholderPainter
import com.v1per.synthx.ui.components.crossfadeModel
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TileSizes
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun RecentlyAddedSection(songs: ImmutableList<Song>, onSeeAllClick: () -> Unit, onSongClick: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding)
                .padding(top = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_recently_added),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        RecentTileRows(tileCount = { songs.size }) { songIndex, tileModifier ->
            val song = songs[songIndex]
            val onClick = remember(song.id, songIndex, onSongClick) {
                { onSongClick(songIndex) }
            }
            RecentSongTile(song = song, onClick = onClick, modifier = tileModifier)
        }

        Spacer(modifier = Modifier.height(Spacing.ItemSpacing))

        // Trailing action at the end of the limited preview. Reuses CategoryRow so it shares the
        // Accent ripple, padding and hairline; the 48dp floor is for this row only.
        CategoryRow(
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            label = stringResource(R.string.action_see_all),
            onClick = onSeeAllClick,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(RecentlyAddedSeeAllTag),
        )
    }
}

internal const val RecentlyAddedSeeAllTag = "recently_added_see_all"
internal const val RecentlyAddedGridTag = "recently_added_grid"
internal const val RecentlyAddedSkeletonTileTag = "recently_added_skeleton_tile"

/**
 * The tile grid shared by the section and its skeleton. It reads the section's full width and lays
 * [tileCount] tiles (given the column count) out in rows of [recentTileGrid] columns. Every row has
 * one equal-weight slot per column, blank after a short last row's final tile, so the columns line
 * up and the side margins, gaps and tiles always add up to the width without overflowing.
 */
@Composable
private fun RecentTileRows(
    tileCount: (columns: Int) -> Int,
    tile: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val grid = remember(maxWidth) { recentTileGrid(maxWidth) }
        Column(
            modifier = Modifier
                .testTag(RecentlyAddedGridTag)
                .fillMaxWidth()
                .padding(horizontal = TileSizes.SideMargin + grid.edge),
            verticalArrangement = Arrangement.spacedBy(grid.gap),
        ) {
            recentTileRows(tileCount(grid.columns), grid.columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(grid.gap),
                ) {
                    row.forEach { index -> tile(index, Modifier.weight(1f)) }
                    repeat(grid.columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

// Matches RecentlyAddedSection's header/grid shape while data is still loading. The "See
// all" action is omitted rather than shown disabled — there's nothing to see all of yet.
private const val SkeletonPulseDurationMillis = 1400

/** An invisible stand-in that gives a skeleton text line the real line's height. */
private const val SkeletonLinePlaceholder = "Ag"

/** The share of its line's height a skeleton bar covers, centred, so stacked bars read apart. */
private const val SkeletonBarHeightFraction = 0.7f

@Composable
internal fun RecentlyAddedSectionSkeleton() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.library_recently_added),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier
                .padding(horizontal = Spacing.HorizontalPadding)
                .padding(top = Spacing.lg),
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        RecentTileRows(tileCount = ::recentSkeletonTileCount) { _, tileModifier ->
            RecentSongTileSkeleton(modifier = tileModifier)
        }
    }
}

@Composable
internal fun RecentlyAddedSectionEmpty() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.library_recently_added),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier
                .padding(horizontal = Spacing.HorizontalPadding)
                .padding(top = Spacing.lg),
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.empty_recently_added),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        }
    }
}

/** Softly pulsing neutral placeholder matching a [RecentSongTile]'s silhouette. */
@Composable
private fun RecentSongTileSkeleton(modifier: Modifier = Modifier) {
    val pulseAlpha by rememberInfiniteTransition(label = "recentlyAddedSkeletonPulse")
        .animateFloat(
            initialValue = 0.65f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = SkeletonPulseDurationMillis,
                    easing = Motion.EasingStandard,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recentlyAddedSkeletonAlpha",
        )

    Column(modifier = modifier.testTag(RecentlyAddedSkeletonTileTag)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
                .background(SurfaceVariant.copy(alpha = pulseAlpha)),
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        SkeletonTextLine(
            style = MaterialTheme.typography.labelMedium,
            widthFraction = 0.7f,
            color = TextSecondary.copy(alpha = 0.24f * pulseAlpha),
        )
        SkeletonTextLine(
            style = MaterialTheme.typography.labelSmall,
            widthFraction = 0.5f,
            color = TextSecondary.copy(alpha = 0.14f * pulseAlpha),
        )
    }
}

/**
 * One skeleton text line, exactly as tall as a real tile's line in [style] at any font scale: an
 * invisible one-line placeholder laid out in that style, with the bar painted inside its box.
 */
@Composable
private fun SkeletonTextLine(style: TextStyle, widthFraction: Float, color: Color) {
    val shape = MaterialTheme.shapes.small
    Text(
        text = SkeletonLinePlaceholder,
        style = style,
        color = Color.Transparent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .clearAndSetSemantics {}
            .drawBehind {
                val bar = Size(size.width, size.height * SkeletonBarHeightFraction)
                translate(top = (size.height - bar.height) / 2) {
                    drawOutline(shape.createOutline(bar, layoutDirection, this), color)
                }
            },
    )
}

@Composable
private fun RecentSongTile(song: Song, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .then(
                if (song.isMissing) {
                    Modifier
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .alpha(if (song.isMissing) Alpha.Disabled else 1f),
    ) {
        if (song.albumArtUri != null) {
            AsyncImage(
                model = crossfadeModel(song.albumArtUri),
                contentDescription = null,
                error = ArtPlaceholderPainter,
                fallback = ArtPlaceholderPainter,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                ArtPlaceholder(size = maxWidth)
            }
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = song.title,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artist,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
