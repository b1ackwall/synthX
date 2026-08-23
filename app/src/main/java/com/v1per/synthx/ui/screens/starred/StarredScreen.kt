package com.v1per.synthx.ui.screens.starred

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.SongActionSource
import com.v1per.synthx.ui.components.SongRow
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun StarredScreen(
    topBar: @Composable () -> Unit,
    playbackViewModel: PlaybackViewModel,
    viewModel: StarredViewModel = viewModel(
        factory = StarredViewModel.Factory(LocalContext.current, playbackViewModel),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentlyPlayingSongId by viewModel.currentlyPlayingSongId.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()

        StarredScreenContent(
            uiState = uiState,
            currentlyPlayingSongId = currentlyPlayingSongId,
            onSongTap = viewModel::onSongTap,
        )
    }
}

@Composable
private fun StarredScreenContent(
    uiState: UiState<List<Song>>,
    currentlyPlayingSongId: String?,
    onSongTap: (Song) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> LoadingIndicator()
            // Two lines rather than the one-liners the other screens use: an empty Starred
            // list is the *good* outcome (everything is filed), so the copy explains what
            // the screen is for instead of reading as a failure to find anything.
            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_no_starred),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Spacing.HorizontalPadding),
            )
            is UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = Error,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Success -> StarredList(
                songs = uiState.data,
                currentlyPlayingSongId = currentlyPlayingSongId,
                onSongTap = onSongTap,
            )
        }
    }
}

@Composable
private fun StarredList(
    songs: List<Song>,
    currentlyPlayingSongId: String?,
    onSongTap: (Song) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.md),
    ) {
        items(songs, key = { it.id }) { song ->
            // Remembered per song id for the same reason SongsScreen does it: an unrelated
            // recomposition must not hand this row a fresh lambda and defeat row-level skip.
            val onClick = remember(song.id, onSongTap) { { onSongTap(song) } }
            SongRow(
                song = song,
                isCurrentlyPlaying = song.id == currentlyPlayingSongId,
                onClick = onClick,
                // The only thing that distinguishes these rows from the Songs screen's:
                // it adds "Remove from Starred" to the shared actions sheet.
                source = SongActionSource.Starred,
            )
        }
    }
}

@Preview(name = "Starred Screen — Success", showBackground = true)
@Composable
private fun StarredScreenPreview() {
    val sampleSongs = listOf(
        Song(id = "1", title = "Neon Pulse", artist = "v1per", album = "Ultraviolet", duration = 243000L, filePath = ""),
        Song(id = "2", title = "Static Bloom", artist = "KNTRL", album = "Fragments", duration = 198000L, filePath = ""),
        Song(id = "3", title = "Low Orbit", artist = "Null Signal", album = "Deep Cut", duration = 272000L, filePath = ""),
    )
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            StarredScreenContent(
                uiState = UiState.Success(data = sampleSongs),
                currentlyPlayingSongId = "1",
                onSongTap = {},
            )
        }
    }
}

@Preview(name = "Starred Screen — Empty", showBackground = true)
@Composable
private fun StarredScreenEmptyPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            StarredScreenContent(
                uiState = UiState.Empty,
                currentlyPlayingSongId = null,
                onSongTap = {},
            )
        }
    }
}
