package com.v1per.synthx.ui.screens.artistdetail

import androidx.compose.foundation.background
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
import com.v1per.synthx.ui.components.DetailPlayShuffleRow
import com.v1per.synthx.ui.components.DetailStatsLine
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.SongRow
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun ArtistDetailScreen(
    topBar: @Composable () -> Unit,
    artistId: String,
    playbackViewModel: PlaybackViewModel,
    viewModel: ArtistDetailViewModel = viewModel(
        key = artistId,
        factory = ArtistDetailViewModel.factory(LocalContext.current, artistId),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentSong by playbackViewModel.currentSong.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()

        ArtistDetailContent(
            uiState = uiState,
            currentlyPlayingSongId = currentSong?.id,
            onSongTap = playbackViewModel::requestPlayQueue,
            onPlay = playbackViewModel::playAll,
            onShuffle = playbackViewModel::shuffleAll,
        )
    }
}

// Split from ArtistDetailScreen so it can be composed without a PlaybackViewModel.
@Composable
internal fun ArtistDetailContent(
    uiState: UiState<ArtistDetailData>,
    currentlyPlayingSongId: String?,
    // Takes the whole artist list plus the tapped index: playback queues from there.
    onSongTap: (List<Song>, Int) -> Unit,
    // Take the whole list as displayed; playback drops missing songs and decides the order.
    onPlay: (List<Song>) -> Unit,
    onShuffle: (List<Song>) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is UiState.Loading -> LoadingIndicator()

            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_no_songs),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )

            is UiState.Error -> Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                color = Error,
                modifier = Modifier.align(Alignment.Center),
            )

            // No hero: the stats line sits under the top content padding, and the action row's
            // own vertical padding spaces it from the stats line above and the first row below.
            is UiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.md),
            ) {
                item(key = STATS_ITEM_KEY, contentType = "stats") {
                    DetailStatsLine(
                        songCount = state.data.playableSongCount,
                        totalDurationMs = state.data.playableDurationMs,
                    )
                }
                item(key = ACTIONS_ITEM_KEY, contentType = "actions") {
                    DetailPlayShuffleRow(
                        enabled = state.data.playableSongCount > 0,
                        onPlay = { onPlay(state.data.songs) },
                        onShuffle = { onShuffle(state.data.songs) },
                    )
                }
                itemsIndexed(state.data.songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        song = song,
                        isCurrentlyPlaying = song.id == currentlyPlayingSongId,
                        onClick = { onSongTap(state.data.songs, index) },
                    )
                }
            }
        }
    }
}

private const val STATS_ITEM_KEY = "artist_stats"
private const val ACTIONS_ITEM_KEY = "artist_actions"
