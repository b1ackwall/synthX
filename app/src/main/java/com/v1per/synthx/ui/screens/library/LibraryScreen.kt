package com.v1per.synthx.ui.screens.library

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.collections.immutable.ImmutableList

@Composable
fun LibraryScreen(
    topBar: @Composable () -> Unit,
    onNavigateToSongs: () -> Unit,
    onNavigateToArtistList: () -> Unit,
    onNavigateToAlbumList: () -> Unit,
    onNavigateToPlaylistList: () -> Unit,
    onNavigateToStarred: () -> Unit,
    playbackViewModel: PlaybackViewModel,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(LocalContext.current)),
) {
    val recentSongs by viewModel.recentSongs.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val starredCount by viewModel.starredCount.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.refreshMessages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                NavigationEvent.GoToSongs -> onNavigateToSongs()
                NavigationEvent.GoToArtistList -> onNavigateToArtistList()
                NavigationEvent.GoToAlbumList -> onNavigateToAlbumList()
                NavigationEvent.GoToPlaylistList -> onNavigateToPlaylistList()
                NavigationEvent.GoToStarred -> onNavigateToStarred()
            }
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = topBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::onPullToRefresh,
            modifier = Modifier.padding(
                start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                bottom = innerPadding.calculateBottomPadding(),
            ),
        ) {
            LibraryScreenContent(
                recentSongs = recentSongs,
                error = error,
                isLoading = isLoading,
                starredCount = starredCount,
                onPlaylistsClick = viewModel::onPlaylistsClick,
                onArtistsClick = viewModel::onArtistsClick,
                onAlbumsClick = viewModel::onAlbumsClick,
                onSongsClick = viewModel::onSongsClick,
                onStarredClick = viewModel::onStarredClick,
                onSeeAllClick = onNavigateToSongs,
                onSongClick = { index -> playbackViewModel.requestPlayQueue(recentSongs, index) },
            )
        }
    }
}

@Composable
internal fun LibraryScreenContent(
    modifier: Modifier = Modifier,
    recentSongs: ImmutableList<Song>,
    error: String?,
    isLoading: Boolean,
    starredCount: Int,
    onPlaylistsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onAlbumsClick: () -> Unit,
    onSongsClick: () -> Unit,
    onStarredClick: () -> Unit,
    onSeeAllClick: () -> Unit,
    // Takes the tapped song's index in [recentSongs]: the whole section becomes the queue.
    onSongClick: (Int) -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize().background(Background)) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
                modifier = Modifier
                    .padding(horizontal = Spacing.HorizontalPadding)
                    .padding(top = Spacing.lg),
            )
        }

        item(key = "title_spacer") {
            Spacer(modifier = Modifier.height(Spacing.md))
        }

        item(key = "category_playlists") {
            CategoryRow(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = stringResource(R.string.screen_playlists),
                onClick = onPlaylistsClick,
            )
        }
        item(key = "category_artists") {
            CategoryRow(
                icon = Icons.Default.Person,
                label = stringResource(R.string.screen_artists),
                onClick = onArtistsClick,
            )
        }
        item(key = "category_albums") {
            CategoryRow(
                icon = Icons.Default.Album,
                label = stringResource(R.string.screen_albums),
                onClick = onAlbumsClick,
            )
        }
        item(key = "category_songs") {
            CategoryRow(
                icon = Icons.Default.MusicNote,
                label = stringResource(R.string.screen_songs),
                onClick = onSongsClick,
            )
        }

        // Last of the five: the most specialized category, and the only one that is a
        // working queue rather than a way of browsing the whole library.
        item(key = "category_starred") {
            CategoryRow(
                icon = Icons.Default.StarBorder,
                label = stringResource(R.string.screen_starred),
                // Badge only when there is something to file — same convention as IssuesRow.
                trailingText = if (starredCount > 0) starredCount.toString() else null,
                onClick = onStarredClick,
            )
        }

        item(key = "error") {
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

        item(key = "recently_added") {
            if (isLoading) {
                RecentlyAddedSectionSkeleton()
            } else if (recentSongs.isNotEmpty()) {
                RecentlyAddedSection(
                    songs = recentSongs,
                    onSeeAllClick = onSeeAllClick,
                    onSongClick = onSongClick,
                )
            } else {
                RecentlyAddedSectionEmpty()
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}

/** @param trailingText optional count badge, rendered right-aligned; null hides it. */
@Composable
internal fun CategoryRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailingText: String? = null,
    modifier: Modifier = Modifier,
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
                .then(modifier)
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
            trailingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}
