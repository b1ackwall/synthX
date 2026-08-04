package com.v1per.synthx.ui.screens.groupdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.PlaylistRow
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun GroupDetailScreen(
    topBar: @Composable () -> Unit,
    onPlaylistClick: (playlistId: String, playlistName: String) -> Unit,
    groupId: String,
) {
    val viewModel: GroupDetailViewModel = viewModel(
        key = groupId,
        factory = GroupDetailViewModel.factory(LocalContext.current, groupId),
    )
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()

        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = uiState) {
                is UiState.Loading -> Unit
                is UiState.Empty -> Text(
                    text = stringResource(R.string.empty_group_no_playlists),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
                is UiState.Success -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.data, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.id, playlist.name) },
                            onRename = { name -> viewModel.renamePlaylist(playlist.id, name) },
                            onDelete = { viewModel.deletePlaylist(playlist.id) },
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
