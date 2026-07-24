package com.v1per.synthx.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingSheet(
    song: Song,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
    onPlayPauseClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    var totalDrag by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (totalDrag > 150f) onDismiss()
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                ) { _, dragAmount ->
                    if (dragAmount > 0f) totalDrag += dragAmount
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(Spacing.xxl))

        val artSize = LocalConfiguration.current.screenWidthDp.dp - Spacing.HorizontalPadding * 2

        with(sharedTransitionScope) {
            if (song.albumArtUri != null) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(artSize)
                        .clip(RoundedCornerShape(4.dp))
                        .sharedElement(
                            state = rememberSharedContentState(key = "album_art"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ ->
                                tween(durationMillis = Motion.DurationNormal)
                            },
                        ),
                )
            } else {
                GlitchPlaceholder(
                    songId = song.id,
                    isPlaying = isPlaying,
                    size = artSize,
                    modifier = Modifier.sharedElement(
                        state = rememberSharedContentState(key = "album_art"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ ->
                            tween(durationMillis = Motion.DurationNormal)
                        },
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        Text(
            text = song.title,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(Spacing.xs))

        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
            onClick = onPlayPauseClick,
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Preview(name = "Now Playing — glitch art", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NowPlayingSheetPreview() {
    SynthXTheme {
        SharedTransitionLayout {
            androidx.compose.animation.AnimatedVisibility(visible = true) {
                NowPlayingSheet(
                    song = Song(
                        id = "preview_1",
                        title = "Neon Pulse",
                        artist = "v1per",
                        album = "Ultraviolet",
                        duration = 243_000L,
                        filePath = "",
                        albumArtUri = null,
                    ),
                    isPlaying = true,
                    onDismiss = {},
                    onPlayPauseClick = {},
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                )
            }
        }
    }
}
