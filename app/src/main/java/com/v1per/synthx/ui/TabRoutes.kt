package com.v1per.synthx.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.SynthXTopBar
import com.v1per.synthx.ui.screens.home.HomeScreen
import com.v1per.synthx.ui.screens.library.LibraryScreen
import com.v1per.synthx.ui.screens.library.LibraryViewModel
import com.v1per.synthx.ui.screens.search.SearchScreen
import com.v1per.synthx.ui.screens.search.SearchViewModel
import com.v1per.synthx.ui.screens.settings.SettingsScreen
import com.v1per.synthx.ui.screens.developersettings.DeveloperSettingsScreen
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.R
import androidx.compose.ui.res.stringResource

fun NavGraphBuilder.tabRoutes(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    chromeBottomPadding: Dp,
) {
    composable(Screen.Home.route) {
        RouteContent(chromeBottomPadding) {
            HomeScreen()
        }
    }
    composable(Screen.Library.route) {
        RouteContent(chromeBottomPadding) {
            val libraryViewModel = viewModel<LibraryViewModel>(
                factory = LibraryViewModel.factory(LocalContext.current),
            )
            LibraryScreen(
                topBar = {
                    SynthXTopBar(
                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    )
                },
                onNavigateToSongs = { navController.navigate(Screen.Songs.route) },
                onNavigateToArtistList = { navController.navigate(Screen.ArtistList.route) },
                onNavigateToAlbumList = { navController.navigate(Screen.AlbumList.route) },
                onNavigateToPlaylistList = { navController.navigate(Screen.PlaylistList.route) },
                onNavigateToStarred = { navController.navigate(Screen.Starred.route) },
                playbackViewModel = playbackViewModel,
                viewModel = libraryViewModel,
            )
        }
    }
    composable(Screen.Search.route) {
        RouteContent(chromeBottomPadding) {
            val searchViewModel = viewModel<SearchViewModel>(
                factory = SearchViewModel.factory(LocalContext.current),
            )
            SearchScreen(
                topBar = {
                    SynthXTopBar(
                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    )
                },
                onNavigateToArtistDetail = { artistId, artistName ->
                    navController.navigate(
                        Screen.ArtistDetail.createRoute(artistId, artistName),
                    )
                },
                onNavigateToAlbumDetail = { albumId, albumTitle ->
                    navController.navigate(
                        Screen.AlbumDetail.createRoute(albumId, albumTitle),
                    )
                },
                onNavigateToPlaylistDetail = { playlistId, playlistName ->
                    navController.navigate(
                        Screen.PlaylistDetail.createRoute(playlistId, playlistName),
                    )
                },
                playbackViewModel = playbackViewModel,
                viewModel = searchViewModel,
            )
        }
    }
    composable(Screen.Settings.route) {
        RouteContent(chromeBottomPadding) {
            SettingsScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_settings),
                        onBack = { navController.popBackStack() },
                    )
                },
                onLibraryManagementClick = {
                    navController.navigate(Screen.LibraryManagement.route)
                },
                onDeveloperSettingsClick = {
                    navController.navigate(Screen.DeveloperSettings.route)
                },
            )
        }
    }
    composable(Screen.DeveloperSettings.route) {
        RouteContent(chromeBottomPadding) {
            DeveloperSettingsScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_developer_settings),
                        onBack = { navController.popBackStack() },
                    )
                },
            )
        }
    }
}
