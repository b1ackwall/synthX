package com.v1per.synthx.ui

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
import com.v1per.synthx.ui.components.ReadinessCoverOverlay
import com.v1per.synthx.ui.screens.addsongs.AddSongsScreen
import com.v1per.synthx.ui.screens.addsongs.AddSongsViewModel
import com.v1per.synthx.ui.screens.createplaylist.CreatePlaylistScreen
import com.v1per.synthx.ui.screens.groupdetail.GroupDetailScreen
import com.v1per.synthx.ui.screens.groupdetail.GroupDetailViewModel
import com.v1per.synthx.ui.screens.playlistdetail.PlaylistDetailScreen
import com.v1per.synthx.ui.screens.playlistdetail.PlaylistDetailViewModel
import com.v1per.synthx.ui.screens.playlistlist.PlaylistListScreen
import com.v1per.synthx.ui.screens.playlistlist.PlaylistListViewModel

fun NavGraphBuilder.playlistRoutes(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    chromeBottomPadding: Dp,
) {
    composable(Screen.PlaylistList.route) {
        RouteContent(chromeBottomPadding) {
            val playlistListViewModel = viewModel<PlaylistListViewModel>(
                factory = PlaylistListViewModel.factory(LocalContext.current),
            )
            PlaylistListScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_playlists),
                        onBack = { navController.popBackStack() },
                    )
                },
                onGroupClick = { groupId, groupName ->
                    navController.navigate(
                        Screen.GroupDetail.createRoute(groupId, groupName),
                    )
                },
                onPlaylistClick = { playlistId, playlistName ->
                    navController.navigate(
                        Screen.PlaylistDetail.createRoute(playlistId, playlistName),
                    )
                },
                onCreatePlaylistClick = {
                    navController.navigate(Screen.CreatePlaylist.route)
                },
                viewModel = playlistListViewModel,
            )
        }
    }
    composable(Screen.CreatePlaylist.route) {
        RouteContent(chromeBottomPadding) {
            CreatePlaylistScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_new_playlist),
                        onBack = { navController.popBackStack() },
                    )
                },
                onCreated = { navController.popBackStack() },
            )
        }
    }
    composable(
        route = Screen.GroupDetail.ROUTE,
        arguments = listOf(
            navArgument("groupId") { type = NavType.StringType },
            navArgument("groupName") { type = NavType.StringType },
        ),
    ) { entry ->
        val groupId = entry.requireStringArg("groupId")
        val groupName = entry.requireStringArg("groupName")
        RouteContent(chromeBottomPadding) {
            val groupDetailViewModel = viewModel<GroupDetailViewModel>(
                key = groupId,
                factory = GroupDetailViewModel.factory(LocalContext.current, groupId),
            )
            val uiState by groupDetailViewModel.uiState.collectAsState()
            ReadinessCoverOverlay(isReady = uiState.isReady()) {
                GroupDetailScreen(
                    topBar = {
                        DetailTopBar(
                            title = groupName,
                            onBack = { navController.popBackStack() },
                        )
                    },
                    onPlaylistClick = { playlistId, playlistName ->
                        navController.navigate(
                            Screen.PlaylistDetail.createRoute(playlistId, playlistName),
                        )
                    },
                    groupId = groupId,
                    viewModel = groupDetailViewModel,
                )
            }
        }
    }
    composable(
        route = Screen.PlaylistDetail.ROUTE,
        arguments = listOf(
            navArgument("playlistId") { type = NavType.StringType },
            navArgument("playlistName") { type = NavType.StringType },
        ),
    ) { entry ->
        val playlistId = entry.requireStringArg("playlistId")
        val playlistName = entry.requireStringArg("playlistName")
        RouteContent(chromeBottomPadding) {
            val playlistDetailViewModel = viewModel<PlaylistDetailViewModel>(
                key = playlistId,
                factory = PlaylistDetailViewModel.factory(LocalContext.current, playlistId),
            )
            val uiState by playlistDetailViewModel.uiState.collectAsState()
            ReadinessCoverOverlay(isReady = uiState.isReady()) {
                PlaylistDetailScreen(
                    topBar = {
                        DetailTopBar(
                            title = playlistName,
                            onBack = { navController.popBackStack() },
                        )
                    },
                    playlistId = playlistId,
                    playbackViewModel = playbackViewModel,
                    onAddSongsClick = {
                        navController.navigate(Screen.AddSongs.createRoute(playlistId))
                    },
                    viewModel = playlistDetailViewModel,
                )
            }
        }
    }
    composable(
        route = Screen.AddSongs.ROUTE,
        arguments = listOf(
            navArgument("playlistId") { type = NavType.StringType },
        ),
    ) { entry ->
        val playlistId = entry.requireStringArg("playlistId")
        RouteContent(chromeBottomPadding) {
            val addSongsViewModel = viewModel<AddSongsViewModel>(
                key = playlistId,
                factory = AddSongsViewModel.factory(LocalContext.current, playlistId),
            )
            val isLoading by addSongsViewModel.isLoading.collectAsState()
            ReadinessCoverOverlay(isReady = !isLoading) {
                AddSongsScreen(
                    topBar = {
                        DetailTopBar(
                            title = stringResource(R.string.screen_add_songs),
                            onBack = { navController.popBackStack() },
                        )
                    },
                    playlistId = playlistId,
                    onSubmitted = { navController.popBackStack() },
                    viewModel = addSongsViewModel,
                )
            }
        }
    }
}
