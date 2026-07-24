package com.v1per.synthx.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import coil.compose.AsyncImage
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Height of the collapsed mini-player bar. Exposed so MainScaffold can reserve the
 * matching bottom inset on scrollable content behind the sheet.
 */
val CollapsedPlayerHeight: Dp = 64.dp

enum class PlayerSheetValue { Collapsed, Expanded }

/**
 * A single draggable player surface that morphs between a 64dp mini player (Collapsed)
 * and a full-screen player (Expanded). Every visual property lerps off one [progress]
 * float derived from an [AnchoredDraggableState] — there is no swap between two
 * composables, no shared-element transition.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerSheet(
    song: Song,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }
    val decayAnimationSpec = remember { exponentialDecay<Float>() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()

        val draggableState = remember {
            AnchoredDraggableState(
                initialValue = PlayerSheetValue.Collapsed,
                positionalThreshold = { totalDistance -> totalDistance * 0.4f },
                velocityThreshold = { with(density) { 200.dp.toPx() } },
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                decayAnimationSpec = decayAnimationSpec,
            )
        }

        // Anchors depend on the measured height, which is only known inside BoxWithConstraints.
        LaunchedEffect(availableHeightPx) {
            if (availableHeightPx > 0f) {
                draggableState.updateAnchors(
                    DraggableAnchors {
                        PlayerSheetValue.Collapsed at availableHeightPx - collapsedHeightPx
                        PlayerSheetValue.Expanded at 0f
                    },
                )
            }
        }

        // 0f = fully collapsed, 1f = fully expanded.
        val progress by remember(availableHeightPx, collapsedHeightPx) {
            derivedStateOf {
                val offset = draggableState.offset
                if (offset.isNaN()) return@derivedStateOf 0f
                val collapsedAnchor = availableHeightPx - collapsedHeightPx
                if (collapsedAnchor <= 0f) 0f
                else ((collapsedAnchor - offset) / collapsedAnchor).coerceIn(0f, 1f)
            }
        }

        PlayerSheetContent(
            song = song,
            isPlaying = isPlaying,
            progress = progress,
            fullWidth = maxWidth,
            fullHeight = maxHeight,
            onPlayPauseClick = onPlayPauseClick,
            onExpand = { scope.launch { draggableState.animateTo(PlayerSheetValue.Expanded) } },
            sheetModifier = Modifier.anchoredDraggable(draggableState, Orientation.Vertical),
        )
    }
}

@Composable
private fun PlayerSheetContent(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    fullWidth: Dp,
    fullHeight: Dp,
    onPlayPauseClick: () -> Unit,
    onExpand: () -> Unit,
    sheetModifier: Modifier = Modifier,
) {
    val sheetHeight = lerp(CollapsedPlayerHeight, fullHeight, progress)
    val artSize = lerp(44.dp, fullWidth - Spacing.HorizontalPadding * 2, progress)
    val cornerRadius = lerp(0.dp, 24.dp, progress) // flat when collapsed, rounded top when expanded
    val miniPlayerAlpha = (1f - progress * 3f).coerceIn(0f, 1f) // fades out over the first third
    val fullPlayerAlpha = ((progress - 0.4f) * (1f / 0.6f)).coerceIn(0f, 1f) // fades in after 40%

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = sheetModifier
                .fillMaxWidth()
                .height(sheetHeight)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                .background(Surface),
        ) {
            // Top neon hairline — the sheet's defining edge, present in every state.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Hairline)
                    .align(Alignment.TopCenter),
            )

            // COLLAPSED — mini player row, tap anywhere (but the button) to expand.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CollapsedPlayerHeight)
                    .align(Alignment.TopCenter)
                    .alpha(miniPlayerAlpha)
                    .clickable(enabled = miniPlayerAlpha > 0.5f, onClick = onExpand)
                    .padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(song = song, isPlaying = isPlaying, size = 44.dp)
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onPlayPauseClick,
                    enabled = miniPlayerAlpha > 0.5f,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isPlaying) Accent else TextPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // EXPANDED — full player.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(fullPlayerAlpha)
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.HorizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(Spacing.xl))
                AlbumArt(song = song, isPlaying = isPlaying, size = artSize)
                Spacer(modifier = Modifier.height(Spacing.xl))
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onPlayPauseClick,
                    enabled = fullPlayerAlpha > 0.5f,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isPlaying) Accent else TextPrimary,
                        modifier = Modifier.size(48.dp),
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.xxl))
            }
        }
    }
}

@Composable
private fun AlbumArt(song: Song, isPlaying: Boolean, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(2.dp)),
    ) {
        if (song.albumArtUri != null) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GlitchPlaceholder(
                songId = song.id,
                isPlaying = isPlaying,
                size = size,
            )
        }
    }
}

private val PreviewSong = Song(
    id = "preview_1",
    title = "Neon Pulse",
    artist = "v1per",
    album = "Ultraviolet",
    duration = 243_000L,
    filePath = "",
    albumArtUri = null,
)

@Preview(name = "PlayerSheet — Collapsed", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerSheetCollapsedPreview() {
    SynthXTheme {
        Box(modifier = Modifier.size(width = 400.dp, height = 800.dp)) {
            PlayerSheetContent(
                song = PreviewSong,
                isPlaying = false,
                progress = 0f,
                fullWidth = 400.dp,
                fullHeight = 800.dp,
                onPlayPauseClick = {},
                onExpand = {},
            )
        }
    }
}

@Preview(name = "PlayerSheet — Expanded", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerSheetExpandedPreview() {
    SynthXTheme {
        Box(modifier = Modifier.size(width = 400.dp, height = 800.dp)) {
            PlayerSheetContent(
                song = PreviewSong,
                isPlaying = true,
                progress = 1f,
                fullWidth = 400.dp,
                fullHeight = 800.dp,
                onPlayPauseClick = {},
                onExpand = {},
            )
        }
    }
}
