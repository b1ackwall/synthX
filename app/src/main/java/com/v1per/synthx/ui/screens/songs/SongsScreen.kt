package com.v1per.synthx.ui.screens.songs

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
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.GlitchPlaceholder
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun SongsScreen(playbackViewModel: PlaybackViewModel) {
    val context = LocalContext.current
    val viewModel: SongsViewModel = viewModel(
        factory = SongsViewModel.Factory(context, playbackViewModel),
    )
    val uiState by viewModel.uiState.collectAsState()
    val currentlyPlayingSongId by viewModel.currentlyPlayingSongId.collectAsState()

    SongsScreenContent(
        uiState = uiState,
        currentlyPlayingSongId = currentlyPlayingSongId,
        onSongTap = viewModel::onSongTap,
    )
}

@Composable
private fun SongsScreenContent(
    uiState: UiState,
    currentlyPlayingSongId: String?,
    onSongTap: (Song) -> Unit,
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
                onSongTap = onSongTap,
            )
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    currentlyPlayingSongId: String?,
    onSongTap: (Song) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.md),
    ) {
        items(songs, key = { it.id }) { song ->
            SongRow(
                song = song,
                isCurrentlyPlaying = song.id == currentlyPlayingSongId,
                onClick = { onSongTap(song) },
            )
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
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
                    onClick = onClick,
                )
                .padding(vertical = Spacing.sm),
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
                onSongTap = {},
            )
        }
    }
}
