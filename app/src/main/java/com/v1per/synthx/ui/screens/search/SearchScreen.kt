package com.v1per.synthx.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.GlitchPlaceholder
import com.v1per.synthx.ui.components.PlaylistRow
import com.v1per.synthx.ui.components.SongRow
import com.v1per.synthx.ui.screens.search.SearchViewModel.SearchFilter
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun SearchScreen(
    topBar: @Composable () -> Unit,
    onNavigateToArtistDetail: (artistId: String, artistName: String) -> Unit,
    onNavigateToAlbumDetail: (albumId: String, albumTitle: String) -> Unit,
    onNavigateToPlaylistDetail: (playlistId: String, playlistName: String) -> Unit,
    playbackViewModel: PlaybackViewModel,
    viewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.factory(LocalContext.current),
    ),
) {
    val query by viewModel.query.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val results by viewModel.results.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentSong by playbackViewModel.currentSong.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text(text = "Search your library", color = TextTertiary) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = TextSecondary,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = TextSecondary,
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TextSecondary,
                cursorColor = TextPrimary,
            ),
            shape = MaterialTheme.shapes.small,
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(SearchFilter.entries) { filterOption ->
                FilterChip(
                    selected = filter == filterOption,
                    onClick = { viewModel.onFilterChange(filterOption) },
                    label = {
                        Text(
                            text = when (filterOption) {
                                SearchFilter.ALL -> "All"
                                SearchFilter.SONGS -> "Songs"
                                SearchFilter.PLAYLISTS -> "Playlists"
                                SearchFilter.ARTISTS -> "Artists"
                                SearchFilter.ALBUMS -> "Albums"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TextPrimary,
                        selectedLabelColor = Background,
                        containerColor = SurfaceVariant,
                        labelColor = TextSecondary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == filterOption,
                        borderColor = Color.Transparent,
                        selectedBorderColor = Color.Transparent,
                    ),
                    shape = MaterialTheme.shapes.small,
                )
            }
        }

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = Error,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.sm,
                ),
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                query.isBlank() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Search your library",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }

                results.isEmpty -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No results found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    when (filter) {
                        SearchFilter.SONGS -> songItems(
                            songs = results.songs,
                            currentSong = currentSong,
                            playbackViewModel = playbackViewModel,
                        )
                        SearchFilter.ARTISTS -> artistItems(
                            artists = results.artists,
                            onNavigateToArtistDetail = onNavigateToArtistDetail,
                        )
                        SearchFilter.ALBUMS -> albumItems(
                            albums = results.albums,
                            onNavigateToAlbumDetail = onNavigateToAlbumDetail,
                        )
                        SearchFilter.PLAYLISTS -> playlistItems(
                            playlists = results.playlists,
                            onNavigateToPlaylistDetail = onNavigateToPlaylistDetail,
                        )
                        SearchFilter.ALL -> {
                            if (results.playlists.isNotEmpty()) {
                                sectionHeader("Playlists")
                                playlistItems(results.playlists, onNavigateToPlaylistDetail)
                            }
                            if (results.artists.isNotEmpty()) {
                                sectionHeader("Artists")
                                artistItems(results.artists, onNavigateToArtistDetail)
                            }
                            if (results.albums.isNotEmpty()) {
                                sectionHeader("Albums")
                                albumItems(results.albums, onNavigateToAlbumDetail)
                            }
                            if (results.songs.isNotEmpty()) {
                                sectionHeader("Songs")
                                songItems(results.songs, currentSong, playbackViewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header_$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm),
        )
    }
}

private fun LazyListScope.songItems(
    songs: List<Song>,
    currentSong: Song?,
    playbackViewModel: PlaybackViewModel,
) {
    items(songs, key = { "song_${it.id}" }) { song ->
        SongRow(
            song = song,
            isCurrentlyPlaying = song.id == currentSong?.id,
            onClick = { playbackViewModel.playSong(song) },
        )
    }
}

private fun LazyListScope.artistItems(
    artists: List<Artist>,
    onNavigateToArtistDetail: (String, String) -> Unit,
) {
    items(artists, key = { "artist_${it.id}" }) { artist ->
        ArtistResultRow(
            artist = artist,
            onClick = { onNavigateToArtistDetail(artist.id, artist.name) },
        )
    }
}

private fun LazyListScope.albumItems(
    albums: List<Album>,
    onNavigateToAlbumDetail: (String, String) -> Unit,
) {
    items(albums, key = { "album_${it.id}" }) { album ->
        AlbumResultRow(
            album = album,
            onClick = { onNavigateToAlbumDetail(album.id, album.title) },
        )
    }
}

private fun LazyListScope.playlistItems(
    playlists: List<Playlist>,
    onNavigateToPlaylistDetail: (String, String) -> Unit,
) {
    items(playlists, key = { "playlist_${it.id}" }) { playlist ->
        PlaylistRow(
            playlist = playlist,
            onClick = { onNavigateToPlaylistDetail(playlist.id, playlist.name) },
            onRename = {},
            onDelete = {},
        )
    }
}

@Composable
private fun ArtistResultRow(
    artist: Artist,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.xxl)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    onClick = onClick,
                )
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Composable
private fun AlbumResultRow(
    album: Album,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    onClick = onClick,
                )
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (album.albumArtUri != null) {
                AsyncImage(
                    model = album.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            } else {
                GlitchPlaceholder(
                    songId = album.id,
                    isPlaying = false,
                    size = 44.dp,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}
