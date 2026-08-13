package com.v1per.synthx.ui.screens.albumdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.SongRow
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun AlbumDetailScreen(
    topBar: @Composable () -> Unit,
    albumId: String,
    playbackViewModel: PlaybackViewModel,
    viewModel: AlbumDetailViewModel = viewModel(
        key = albumId,
        factory = AlbumDetailViewModel.factory(LocalContext.current, albumId),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentSong by playbackViewModel.currentSong.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()

        AlbumDetailContent(
            uiState = uiState,
            currentlyPlayingSongId = currentSong?.id,
            onSongTap = playbackViewModel::requestPlayQueue,
        )
    }
}

@Composable
private fun AlbumDetailContent(
    uiState: UiState<List<Song>>,
    currentlyPlayingSongId: String?,
    // Takes the whole album plus the tapped index: playback queues the album from there.
    onSongTap: (List<Song>, Int) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> LoadingIndicator()
            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_no_songs),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = Error,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.md),
            ) {
                itemsIndexed(uiState.data, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        song = song,
                        isCurrentlyPlaying = song.id == currentlyPlayingSongId,
                        onClick = { onSongTap(uiState.data, index) },
                    )
                }
            }
        }
    }
}
