package com.v1per.synthx.ui.screens.library

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.GlitchPlaceholder
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun LibraryScreen(
    topBar: @Composable () -> Unit,
    onNavigateToSongs: () -> Unit,
    onNavigateToArtistList: () -> Unit,
    onNavigateToAlbumList: () -> Unit,
    onNavigateToPlaylistList: () -> Unit,
    playbackViewModel: PlaybackViewModel,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(LocalContext.current)),
) {
    val recentSongs by viewModel.recentSongs.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                NavigationEvent.GoToSongs -> onNavigateToSongs()
                NavigationEvent.GoToArtistList -> onNavigateToArtistList()
                NavigationEvent.GoToAlbumList -> onNavigateToAlbumList()
                NavigationEvent.GoToPlaylistList -> onNavigateToPlaylistList()
            }
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = topBar,
    ) { innerPadding ->
        LibraryScreenContent(
            modifier = Modifier.padding(
                start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                bottom = innerPadding.calculateBottomPadding(),
            ),
            recentSongs = recentSongs,
            error = error,
            onPlaylistsClick = viewModel::onPlaylistsClick,
            onArtistsClick = viewModel::onArtistsClick,
            onAlbumsClick = viewModel::onAlbumsClick,
            onSongsClick = viewModel::onSongsClick,
            onSeeAllClick = onNavigateToSongs,
            onSongClick = playbackViewModel::playSong,
        )
    }
}

@Composable
private fun LibraryScreenContent(
    modifier: Modifier = Modifier,
    recentSongs: List<Song>,
    error: String?,
    onPlaylistsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onAlbumsClick: () -> Unit,
    onSongsClick: () -> Unit,
    onSeeAllClick: () -> Unit,
    onSongClick: (Song) -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
                modifier = Modifier
                    .padding(horizontal = Spacing.HorizontalPadding)
                    .padding(top = Spacing.lg),
            )
        }

        item {
            Spacer(modifier = Modifier.height(Spacing.md))
        }

        item {
            CategoryRow(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = stringResource(R.string.screen_playlists),
                onClick = onPlaylistsClick,
            )
        }
        item {
            CategoryRow(
                icon = Icons.Default.Person,
                label = stringResource(R.string.screen_artists),
                onClick = onArtistsClick,
            )
        }
        item {
            CategoryRow(
                icon = Icons.Default.Album,
                label = stringResource(R.string.screen_albums),
                onClick = onAlbumsClick,
            )
        }
        item {
            CategoryRow(
                icon = Icons.Default.MusicNote,
                label = stringResource(R.string.screen_songs),
                onClick = onSongsClick,
            )
        }

        item {
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = Spacing.HorizontalPadding,
                            vertical = Spacing.sm,
                        ),
                )
            }
        }

        item {
            if (recentSongs.isNotEmpty()) {
                RecentlyAddedSection(
                    songs = recentSongs,
                    onSeeAllClick = onSeeAllClick,
                    onSongClick = onSongClick,
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun RecentlyAddedSection(
    songs: List<Song>,
    onSeeAllClick: () -> Unit,
    onSongClick: (Song) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding)
                .padding(top = Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_recently_added),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Text(
                text = stringResource(R.string.action_see_all),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.clickable(onClick = onSeeAllClick),
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        val rows = songs.chunked(2)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.ItemSpacing),
        ) {
            rows.forEach { rowSongs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.ItemSpacing),
                ) {
                    rowSongs.forEach { song ->
                        RecentSongTile(
                            song = song,
                            onClick = { onSongClick(song) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Fill empty space if odd number of songs
                    if (rowSongs.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSongTile(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        if (song.albumArtUri != null) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            // GlitchPlaceholder applies .size() internally, so resolve the
            // square edge from the tile width before handing it down.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                GlitchPlaceholder(
                    songId = song.id,
                    isPlaying = false,
                    size = maxWidth,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artist,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    onClick = onClick,
                )
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.md),
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

@Preview(name = "Library Screen", showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryScreenContent(
                recentSongs = listOf(
                    Song(id = "1", title = "Neon Pulse", artist = "v1per", album = "Ultraviolet", duration = 243000L, filePath = ""),
                    Song(id = "2", title = "Static Bloom", artist = "KNTRL", album = "Fragments", duration = 198000L, filePath = ""),
                ),
                error = null,
                onPlaylistsClick = {},
                onArtistsClick = {},
                onAlbumsClick = {},
                onSongsClick = {},
                onSeeAllClick = {},
                onSongClick = {},
            )
        }
    }
}
