package com.v1per.synthx.ui.screens.artistdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.SongRow
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun ArtistDetailScreen(
    topBar: @Composable () -> Unit,
    artistId: String,
    playbackViewModel: PlaybackViewModel,
) {
    val viewModel: ArtistDetailViewModel = viewModel(
        key = artistId,
        factory = ArtistDetailViewModel.factory(LocalContext.current, artistId),
    )
    val uiState by viewModel.uiState.collectAsState()
    val currentSong by playbackViewModel.currentSong.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()

        ArtistDetailContent(
            uiState = uiState,
            currentlyPlayingSongId = currentSong?.id,
            onSongTap = playbackViewModel::playSong,
        )
    }
}

@Composable
private fun ArtistDetailContent(
    uiState: ArtistDetailViewModel.UiState,
    currentlyPlayingSongId: String?,
    onSongTap: (Song) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is ArtistDetailViewModel.UiState.Loading -> Unit
            is ArtistDetailViewModel.UiState.Empty -> Text(
                text = "No songs found",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is ArtistDetailViewModel.UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is ArtistDetailViewModel.UiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.md),
            ) {
                items(uiState.songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        isCurrentlyPlaying = song.id == currentlyPlayingSongId,
                        onClick = { onSongTap(song) },
                    )
                }
            }
        }
    }
}
