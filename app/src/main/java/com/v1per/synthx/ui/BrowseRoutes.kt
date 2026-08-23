package com.v1per.synthx.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.v1per.synthx.R
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.isReady
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.screens.albumdetail.AlbumDetailScreen
import com.v1per.synthx.ui.screens.albumdetail.AlbumDetailViewModel
import com.v1per.synthx.ui.screens.albumlist.AlbumListScreen
import com.v1per.synthx.ui.screens.albumlist.AlbumListViewModel
import com.v1per.synthx.ui.screens.artistdetail.ArtistDetailScreen
import com.v1per.synthx.ui.screens.artistdetail.ArtistDetailViewModel
import com.v1per.synthx.ui.screens.artistlist.ArtistListScreen
import com.v1per.synthx.ui.screens.artistlist.ArtistListViewModel
import com.v1per.synthx.ui.screens.songs.SongsScreen
import com.v1per.synthx.ui.screens.songs.SongsViewModel
import com.v1per.synthx.ui.screens.songs.SortOrder
import com.v1per.synthx.ui.screens.starred.StarredScreen
import com.v1per.synthx.ui.theme.TextPrimary

fun NavGraphBuilder.browseRoutes(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    chromeBottomPadding: Dp,
    onShowSortSheet: (currentOrder: SortOrder, onOrderSelected: (SortOrder) -> Unit) -> Unit,
) {
    composable(Screen.Songs.route) {
        RouteContent(chromeBottomPadding) {
            val songsViewModel = viewModel<SongsViewModel>(
                factory = SongsViewModel.Factory(LocalContext.current, playbackViewModel),
            )
            val uiState by songsViewModel.uiState.collectAsState()
            // Disabled 2026-08-13: opaque backgrounds fix the double-composition issue this was
            // also masking; re-enable if cold-load flash becomes a problem.
            // ReadinessCoverOverlay(isReady = uiState.isReady()) {
                SongsScreen(
                    topBar = { onSortClick ->
                        DetailTopBar(
                            title = stringResource(R.string.screen_songs),
                            onBack = { navController.popBackStack() },
                            actions = {
                                IconButton(onClick = onSortClick) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = stringResource(R.string.cd_sort),
                                        tint = TextPrimary,
                                    )
                                }
                            },
                        )
                    },
                    playbackViewModel = playbackViewModel,
                    onShowSortSheet = onShowSortSheet,
                    viewModel = songsViewModel,
                )
            // }
        }
    }
    composable(Screen.Starred.route) {
        RouteContent(chromeBottomPadding) {
            StarredScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_starred),
                        onBack = { navController.popBackStack() },
                    )
                },
                playbackViewModel = playbackViewModel,
            )
        }
    }
    composable(Screen.ArtistList.route) {
        RouteContent(chromeBottomPadding) {
            val artistListViewModel = viewModel<ArtistListViewModel>(
                factory = ArtistListViewModel.factory(LocalContext.current),
            )
            val uiState by artistListViewModel.uiState.collectAsState()
            // Disabled 2026-08-13: opaque backgrounds fix the double-composition issue this was
            // also masking; re-enable if cold-load flash becomes a problem.
            // ReadinessCoverOverlay(isReady = uiState.isReady()) {
                ArtistListScreen(
                    topBar = {
                        DetailTopBar(
                            title = stringResource(R.string.screen_artists),
                            onBack = { navController.popBackStack() },
                        )
                    },
                    onArtistClick = { artistId, artistName ->
                        navController.navigate(
                            Screen.ArtistDetail.createRoute(artistId, artistName),
                        )
                    },
                    viewModel = artistListViewModel,
                )
            // }
        }
    }
    composable(
        route = Screen.ArtistDetail.ROUTE,
        arguments = listOf(
            navArgument("artistId") { type = NavType.StringType },
            navArgument("artistName") { type = NavType.StringType },
        ),
    ) { entry ->
        val artistId = entry.requireStringArg("artistId")
        val artistName = entry.requireStringArg("artistName")
        RouteContent(chromeBottomPadding) {
            val artistDetailViewModel = viewModel<ArtistDetailViewModel>(
                key = artistId,
                factory = ArtistDetailViewModel.factory(LocalContext.current, artistId),
            )
            val uiState by artistDetailViewModel.uiState.collectAsState()
            // Disabled 2026-08-13: opaque backgrounds fix the double-composition issue this was
            // also masking; re-enable if cold-load flash becomes a problem.
            // ReadinessCoverOverlay(isReady = uiState.isReady()) {
                ArtistDetailScreen(
                    topBar = {
                        DetailTopBar(
                            title = artistName,
                            onBack = { navController.popBackStack() },
                        )
                    },
                    artistId = artistId,
                    playbackViewModel = playbackViewModel,
                    viewModel = artistDetailViewModel,
                )
            // }
        }
    }
    composable(Screen.AlbumList.route) {
        RouteContent(chromeBottomPadding) {
            val albumListViewModel = viewModel<AlbumListViewModel>(
                factory = AlbumListViewModel.factory(LocalContext.current),
            )
            val uiState by albumListViewModel.uiState.collectAsState()
            // Disabled 2026-08-13: opaque backgrounds fix the double-composition issue this was
            // also masking; re-enable if cold-load flash becomes a problem.
            // ReadinessCoverOverlay(isReady = uiState.isReady()) {
                AlbumListScreen(
                    topBar = {
                        DetailTopBar(
                            title = stringResource(R.string.screen_albums),
                            onBack = { navController.popBackStack() },
                        )
                    },
                    onAlbumClick = { albumId, albumTitle ->
                        navController.navigate(
                            Screen.AlbumDetail.createRoute(albumId, albumTitle),
                        )
                    },
                    viewModel = albumListViewModel,
                )
            // }
        }
    }
    composable(
        route = Screen.AlbumDetail.ROUTE,
        arguments = listOf(
            navArgument("albumId") { type = NavType.StringType },
            navArgument("albumTitle") { type = NavType.StringType },
        ),
    ) { entry ->
        val albumId = entry.requireStringArg("albumId")
        val albumTitle = entry.requireStringArg("albumTitle")
        RouteContent(chromeBottomPadding) {
            val albumDetailViewModel = viewModel<AlbumDetailViewModel>(
                key = albumId,
                factory = AlbumDetailViewModel.factory(LocalContext.current, albumId),
            )
            val uiState by albumDetailViewModel.uiState.collectAsState()
            // Disabled 2026-08-13: opaque backgrounds fix the double-composition issue this was
            // also masking; re-enable if cold-load flash becomes a problem.
            // ReadinessCoverOverlay(isReady = uiState.isReady()) {
                AlbumDetailScreen(
                    topBar = {
                        DetailTopBar(
                            title = albumTitle,
                            onBack = { navController.popBackStack() },
                        )
                    },
                    albumId = albumId,
                    playbackViewModel = playbackViewModel,
                    viewModel = albumDetailViewModel,
                )
            // }
        }
    }
}
