package com.v1per.synthx.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Height of the collapsed mini-player bar. Exposed so MainScaffold can reserve the
 * matching bottom inset on scrollable content behind the sheet.
 */
val CollapsedPlayerHeight: Dp = 64.dp

/** Footprint of the bottom nav (70dp NavigationBar + 1dp divider) the collapsed sheet clears. */
private val BottomNavHeight: Dp = 71.dp

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
    currentPosition: Long,
    duration: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onProgressChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val collapsedHeightPx = with(density) { CollapsedPlayerHeight.toPx() }
    // Higher friction lets the fling decelerate before handing off to the snap spring.
    val decayAnimationSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableHeightPx = constraints.maxHeight.toFloat()

        val draggableState = remember {
            AnchoredDraggableState(
                initialValue = PlayerSheetValue.Collapsed,
                positionalThreshold = { totalDistance -> totalDistance * 0.4f },
                velocityThreshold = { with(density) { 200.dp.toPx() } },
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
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

        // Report progress upward so chrome (e.g. the bottom nav) can slide with expansion.
        LaunchedEffect(progress) {
            onProgressChanged(progress)
        }

        PlayerSheetContent(
            song = song,
            isPlaying = isPlaying,
            currentPosition = currentPosition,
            duration = duration,
            progress = progress,
            fullWidth = maxWidth,
            fullHeight = maxHeight,
            onPlayPauseClick = onPlayPauseClick,
            onSeek = onSeek,
            onExpand = { scope.launch { draggableState.animateTo(PlayerSheetValue.Expanded) } },
            sheetModifier = Modifier.anchoredDraggable(draggableState, Orientation.Vertical),
        )
    }
}

@Composable
private fun PlayerSheetContent(
    song: Song,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    progress: Float,
    fullWidth: Dp,
    fullHeight: Dp,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onExpand: () -> Unit,
    sheetModifier: Modifier = Modifier,
) {
    val sheetHeight = lerp(CollapsedPlayerHeight, fullHeight, progress)
    val artSize = lerp(44.dp, fullWidth - Spacing.HorizontalPadding * 2, progress)
    val cornerRadius = lerp(0.dp, 24.dp, progress) // flat when collapsed, rounded top when expanded
    val miniPlayerAlpha = (1f - progress * 3f).coerceIn(0f, 1f) // fades out over the first third
    val fullPlayerAlpha = ((progress - 0.4f) * (1f / 0.6f)).coerceIn(0f, 1f) // fades in after 40%
    // Collapsed, the sheet stops above the bottom nav; expanded, it drops to the screen
    // edge to cover the nav area (the nav itself slides away in MainScaffold). This inset
    // is driven by the internal progress only, so it never feeds back into the drag anchors.
    val bottomInset = lerp(BottomNavHeight, 0.dp, progress)

    Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)) {
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
                AlbumArt(song = song, isPlaying = isPlaying, size = artSize, fullResolution = true)
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

                // Seek bar section
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { fraction -> onSeek((fraction * duration).toLong()) },
                        enabled = fullPlayerAlpha > 0.5f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = SurfaceVariant,
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(currentPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.lg))

                // Controls row — prev, play/pause, next
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Previous — placeholder
                    IconButton(
                        onClick = {},
                        enabled = fullPlayerAlpha > 0.5f,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = TextPrimary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
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
                    // Next — placeholder
                    IconButton(
                        onClick = {},
                        enabled = fullPlayerAlpha > 0.5f,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = TextPrimary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.lg))

                // Bottom row — queue + more options placeholders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = {}, enabled = fullPlayerAlpha > 0.5f) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(onClick = {}, enabled = fullPlayerAlpha > 0.5f) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))
            }
        }
    }
}

@Composable
private fun AlbumArt(
    song: Song,
    isPlaying: Boolean,
    size: Dp,
    fullResolution: Boolean = false,
) {
    // "Breathe" — the art relaxes inward when paused and springs back on play.
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "artScale",
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(RoundedCornerShape(2.dp)),
    ) {
        if (song.albumArtUri != null) {
            // The mini thumbnail loads at its 44dp display size. The expanded player
            // opts into Size.ORIGINAL so Coil decodes the artwork at full resolution
            // instead of upscaling the small cached thumbnail.
            val model = if (fullResolution) {
                ImageRequest.Builder(LocalContext.current)
                    .data(song.albumArtUri)
                    .size(Size.ORIGINAL)
                    .build()
            } else {
                song.albumArtUri
            }
            AsyncImage(
                model = model,
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
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
                currentPosition = 63_000L,
                duration = 243_000L,
                progress = 0f,
                fullWidth = 400.dp,
                fullHeight = 800.dp,
                onPlayPauseClick = {},
                onSeek = {},
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
                currentPosition = 63_000L,
                duration = 243_000L,
                progress = 1f,
                fullWidth = 400.dp,
                fullHeight = 800.dp,
                onPlayPauseClick = {},
                onSeek = {},
                onExpand = {},
            )
        }
    }
}
