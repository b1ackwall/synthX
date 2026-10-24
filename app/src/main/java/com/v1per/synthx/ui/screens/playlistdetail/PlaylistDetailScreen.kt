package com.v1per.synthx.ui.screens.playlistdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.DetailHeroSize
import com.v1per.synthx.ui.components.DetailPlayShuffleRow
import com.v1per.synthx.ui.components.DetailStatsLine
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.PlaylistArt
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
    viewModel: PlaylistDetailViewModel = viewModel(
        key = playlistId,
        factory = PlaylistDetailViewModel.factory(LocalContext.current, playlistId),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentSong by playbackViewModel.currentSong.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        topBar()

        PlaylistDetailContent(
            uiState = uiState,
            currentlyPlayingSongId = currentSong?.id,
            playlistId = playlistId,
            onSongTap = playbackViewModel::requestPlayQueue,
            onPlay = playbackViewModel::playAll,
            onShuffle = playbackViewModel::shuffleAll,
        )
    }
}

// Split from PlaylistDetailScreen so it can be composed without a PlaybackViewModel.
@Composable
internal fun PlaylistDetailContent(
    uiState: UiState<PlaylistDetailData>,
    currentlyPlayingSongId: String?,
    playlistId: String,
    // Takes the whole playlist plus the tapped index: playback queues the playlist from there.
    onSongTap: (List<Song>, Int) -> Unit,
    // Take the whole playlist; playback drops missing songs and decides the order.
    onPlay: (List<Song>) -> Unit,
    onShuffle: (List<Song>) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is UiState.Loading -> LoadingIndicator()

            // The playlist does not exist; an empty playlist is Success with no songs.
            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_playlist_not_found),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Spacing.HorizontalPadding),
            )

            is UiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.md),
            ) {
                item(key = COVER_ITEM_KEY, contentType = "cover") {
                    PlaylistDetailCover(state.data.playlist)
                }
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
                if (state.data.songs.isEmpty()) {
                    item(key = EMPTY_ITEM_KEY, contentType = "empty") {
                        Text(
                            text = stringResource(R.string.empty_playlist_no_songs),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.xl),
                        )
                    }
                }
                itemsIndexed(state.data.songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        song = song,
                        isCurrentlyPlaying = song.id == currentlyPlayingSongId,
                        onClick = { onSongTap(state.data.songs, index) },
                        // Lets the actions sheet offer "Remove from Playlist" for this
                        // playlist, alongside "Add to Playlist" for adding to a different one.
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

// The hero: a fixed square centred above the list rather than a full-width aspectRatio(1f) fill,
// and never resized while scrolling (the size is part of the art cache key). PlaylistArt and
// ArtPlaceholder both clip to MaterialTheme.shapes.small themselves.
@Composable
private fun PlaylistDetailCover(playlist: Playlist?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md)
            .testTag(PLAYLIST_DETAIL_COVER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        // Null art (a row that predates the art columns, or a playlist that has gone) falls back
        // to ArtPlaceholder inside PlaylistArt, at this same size.
        PlaylistArt(
            hue = playlist?.artHue,
            structureSeed = playlist?.artStructureSeed,
            size = DetailHeroSize,
        )
    }
}

internal const val PLAYLIST_DETAIL_COVER_TAG = "playlist_detail_cover"
private const val COVER_ITEM_KEY = "playlist_cover"
private const val STATS_ITEM_KEY = "playlist_stats"
private const val ACTIONS_ITEM_KEY = "playlist_actions"
private const val EMPTY_ITEM_KEY = "playlist_empty"
