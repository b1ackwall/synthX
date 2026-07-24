package com.v1per.synthx.ui.screens.songs

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun SongsScreen() {
    val context = LocalContext.current
    val viewModel: SongsViewModel = viewModel(
        factory = SongsViewModel.Factory(context, PreferencesManager(context)),
    )
    val uiState by viewModel.uiState.collectAsState()
    val currentlyPlayingSongId by viewModel.currentlyPlayingSongId.collectAsState()

    SongsScreenContent(
        uiState = uiState,
        currentlyPlayingSongId = currentlyPlayingSongId,
    )
}

@Composable
private fun SongsScreenContent(
    uiState: UiState,
    currentlyPlayingSongId: String?,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Empty -> Text(
                text = "No songs found",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Success -> SongList(
                songs = uiState.songs,
                currentlyPlayingSongId = currentlyPlayingSongId,
            )
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    currentlyPlayingSongId: String?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.md),
    ) {
        items(songs, key = { it.id }) { song ->
            SongRow(
                song = song,
                isCurrentlyPlaying = song.id == currentlyPlayingSongId,
            )
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    isCurrentlyPlaying: Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    onClick = {},
                )
                .padding(vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Neon playing indicator bar
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(if (isCurrentlyPlaying) Accent else Color.Transparent),
            )

            // Album art / glitch placeholder
            Spacer(modifier = Modifier.width(Spacing.md))
            if (song.albumArtUri != null) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            } else {
                GlitchPlaceholder(
                    songId = song.id,
                    isPlaying = isCurrentlyPlaying,
                )
            }

            // Song info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.md),
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // More options
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.padding(end = Spacing.md),
            )
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Composable
private fun GlitchPlaceholder(songId: String, isPlaying: Boolean) {
    val strips = remember(songId) {
        val hash = songId.hashCode()
        val count = 4 + ((hash and Int.MAX_VALUE) % 3)
        (0 until count).map { i ->
            val h1 = hash xor (i * 0x9e3779b9.toInt())
            val h2 = h1 * 0x6c62272e.toInt()
            val h3 = h2 xor (h2 ushr 16)
            GlitchStrip(
                yFrac = (h1 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat(),
                heightFrac = 0.04f + ((h2 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()) * 0.22f,
                widthFrac = 0.3f + ((h3 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()) * 0.7f,
                colorIndex = (h1 and Int.MAX_VALUE) % 3,
                offsetSign = if (i % 2 == 0) 1f else -1f,
            )
        }
    }

    val glitchColors = listOf(
        Accent.copy(alpha = 0.6f),
        TextTertiary.copy(alpha = 0.4f),
        SurfaceVariant.copy(alpha = 0.8f),
    )

    // Always running — value only applied when isPlaying
    val infiniteTransition = rememberInfiniteTransition(label = "glitch")
    val animOffset by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.DurationNormal, easing = Motion.EasingStandard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glitch_offset",
    )
    val pixelOffset = if (isPlaying) animOffset else 0f

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(2.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = SurfaceVariant)
            strips.forEach { strip ->
                val y = strip.yFrac * size.height
                val h = strip.heightFrac * size.height
                val w = strip.widthFrac * size.width
                val xOff = strip.offsetSign * pixelOffset.dp.toPx()
                drawRect(
                    color = glitchColors[strip.colorIndex],
                    topLeft = Offset(xOff, y),
                    size = Size(w, h),
                )
            }
        }
    }
}

private data class GlitchStrip(
    val yFrac: Float,
    val heightFrac: Float,
    val widthFrac: Float,
    val colorIndex: Int,
    val offsetSign: Float,
)

@Preview(name = "Songs Screen — Success", showBackground = true)
@Composable
private fun SongsScreenPreview() {
    val sampleSongs = listOf(
        Song(id = "1", title = "Neon Pulse", artist = "v1per", album = "Ultraviolet", duration = 243000L, filePath = ""),
        Song(id = "2", title = "Static Bloom", artist = "KNTRL", album = "Fragments", duration = 198000L, filePath = ""),
        Song(id = "3", title = "Glitch Cascade", artist = "v1per", album = "Ultraviolet", duration = 317000L, filePath = ""),
        Song(id = "4", title = "Low Orbit", artist = "Null Signal", album = "Deep Cut", duration = 272000L, filePath = ""),
    )
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            SongsScreenContent(
                uiState = UiState.Success(sampleSongs),
                currentlyPlayingSongId = "1",
            )
        }
    }
}
