package com.v1per.synthx.ui.screens.playlistdetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.SongRow
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun PlaylistDetailScreen(
    topBar: @Composable () -> Unit,
    playlistId: String,
    playbackViewModel: PlaybackViewModel,
    onAddSongsClick: () -> Unit,
    viewModel: PlaylistDetailViewModel = viewModel(
        key = playlistId,
        factory = PlaylistDetailViewModel.factory(LocalContext.current, playlistId),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentSong by playbackViewModel.currentSong.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        ) {
            OutlinedButton(
                onClick = onAddSongsClick,
                border = BorderStroke(1.dp, TextSecondary),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = TextSecondary,
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.action_add_songs),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = uiState) {
                is UiState.Loading -> LoadingIndicator()
                is UiState.Empty -> Text(
                    text = stringResource(R.string.empty_playlist_no_songs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
                is UiState.Success -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.md),
                ) {
                    itemsIndexed(state.data, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            song = song,
                            isCurrentlyPlaying = song.id == currentSong?.id,
                            onClick = { playbackViewModel.requestPlayQueue(state.data, index) },
                            // Swaps "Add to Playlist" for "Remove from Playlist" in the sheet.
                            currentPlaylistId = playlistId,
                        )
                    }
                }
                is UiState.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = Spacing.HorizontalPadding),
                )
            }
        }
    }
}
