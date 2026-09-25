package com.v1per.synthx.ui.screens.library

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.v1per.synthx.ui.components.ArtPlaceholder
import com.v1per.synthx.ui.components.crossfadeModel
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
private fun LibraryScreenContent(
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

@Composable
private fun RecentlyAddedSection(
    songs: ImmutableList<Song>,
    onSeeAllClick: () -> Unit,
    onSongClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding)
                .padding(top = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_recently_added),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
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
            rows.forEachIndexed { rowIndex, rowSongs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.ItemSpacing),
                ) {
                    rowSongs.forEachIndexed { columnIndex, song ->
                        val songIndex = rowIndex * 2 + columnIndex
                        val onClick = remember(song.id, songIndex, onSongClick) {
                            { onSongClick(songIndex) }
                        }
                        RecentSongTile(
                            song = song,
                            onClick = onClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowSongs.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.ItemSpacing))

        // Trailing action at the end of the limited preview. Reuses CategoryRow so it shares the
        // Accent ripple, padding and hairline; the 48dp floor is for this row only.
        CategoryRow(
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            label = stringResource(R.string.action_see_all),
            onClick = onSeeAllClick,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(RecentlyAddedSeeAllTag),
        )
    }
}

internal const val RecentlyAddedSeeAllTag = "recently_added_see_all"

// Matches RecentlyAddedSection's header/grid shape while data is still loading. The "See
// all" action is omitted rather than shown disabled — there's nothing to see all of yet.
private const val SkeletonTileCount = 6
private const val SkeletonPulseDurationMillis = 1400

@Composable
private fun RecentlyAddedSectionSkeleton() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.library_recently_added),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier
                .padding(horizontal = Spacing.HorizontalPadding)
                .padding(top = Spacing.lg),
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        val rows = (1..SkeletonTileCount).chunked(2)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.ItemSpacing),
        ) {
            rows.forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.ItemSpacing),
                ) {
                    rowSlots.forEach { _ ->
                        RecentSongTileSkeleton(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyAddedSectionEmpty() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.library_recently_added),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier
                .padding(horizontal = Spacing.HorizontalPadding)
                .padding(top = Spacing.lg),
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.empty_recently_added),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        }
    }
}

/** Softly pulsing neutral placeholder matching a [RecentSongTile]'s silhouette. */
@Composable
private fun RecentSongTileSkeleton(modifier: Modifier = Modifier) {
    val pulseAlpha by rememberInfiniteTransition(label = "recentlyAddedSkeletonPulse")
        .animateFloat(
            initialValue = 0.65f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = SkeletonPulseDurationMillis,
                    easing = Motion.EasingStandard,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recentlyAddedSkeletonAlpha",
        )

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
                .background(SurfaceVariant.copy(alpha = pulseAlpha)),
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(10.dp)
                .clip(MaterialTheme.shapes.small)
                .background(TextSecondary.copy(alpha = 0.24f * pulseAlpha)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(8.dp)
                .clip(MaterialTheme.shapes.small)
                .background(TextSecondary.copy(alpha = 0.14f * pulseAlpha)),
        )
    }
}

@Composable
private fun RecentSongTile(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .then(
                if (song.isMissing) {
                    Modifier
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .alpha(if (song.isMissing) Alpha.Disabled else 1f),
    ) {
        if (song.albumArtUri != null) {
            AsyncImage(
                model = crossfadeModel(song.albumArtUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                ArtPlaceholder(size = maxWidth)
            }
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
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

/** @param trailingText optional count badge, rendered right-aligned; null hides it. */
@Composable
private fun CategoryRow(
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

@Preview(name = "Library Screen", showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            LibraryScreenContent(
                recentSongs = persistentListOf(
                    Song(id = "1", title = "Neon Pulse", artist = "v1per", album = "Ultraviolet", duration = 243000L, filePath = ""),
                    Song(id = "2", title = "Static Bloom", artist = "KNTRL", album = "Fragments", duration = 198000L, filePath = ""),
                ),
                error = null,
                isLoading = false,
                starredCount = 12,
                onPlaylistsClick = {},
                onArtistsClick = {},
                onAlbumsClick = {},
                onSongsClick = {},
                onStarredClick = {},
                onSeeAllClick = {},
                onSongClick = {},
            )
        }
    }
}
