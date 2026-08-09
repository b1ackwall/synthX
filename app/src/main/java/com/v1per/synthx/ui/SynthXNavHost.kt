package com.v1per.synthx.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.v1per.synthx.R
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.components.SynthXTopBar
import com.v1per.synthx.ui.screens.addsongs.AddSongsScreen
import com.v1per.synthx.ui.screens.albumdetail.AlbumDetailScreen
import com.v1per.synthx.ui.screens.albumlist.AlbumListScreen
import com.v1per.synthx.ui.screens.artistdetail.ArtistDetailScreen
import com.v1per.synthx.ui.screens.artistlist.ArtistListScreen
import com.v1per.synthx.ui.screens.createplaylist.CreatePlaylistScreen
import com.v1per.synthx.ui.screens.groupdetail.GroupDetailScreen
import com.v1per.synthx.ui.screens.home.HomeScreen
import com.v1per.synthx.ui.screens.issues.IssuesScreen
import com.v1per.synthx.ui.screens.playlistdetail.PlaylistDetailScreen
import com.v1per.synthx.ui.screens.playlistlist.PlaylistListScreen
import com.v1per.synthx.ui.screens.restore.RestoreScreen
import com.v1per.synthx.ui.screens.library.LibraryScreen
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementScreen
import com.v1per.synthx.ui.screens.search.SearchScreen
import com.v1per.synthx.ui.screens.settings.SettingsScreen
import com.v1per.synthx.ui.screens.songs.SongsScreen
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary

@Composable
fun SynthXNavHost(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    chromeBottomPadding: Dp,
) {
    Scaffold(
        containerColor = Background,
        contentColor = TextPrimary,
        topBar = {},
        bottomBar = {},
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Screens render their own TopAppBars now — consume the insets this
                // padding already covers so those bars don't re-apply the status inset.
                .consumeWindowInsets(innerPadding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            coreRoutes(
                navController = navController,
                playbackViewModel = playbackViewModel,
                chromeBottomPadding = chromeBottomPadding,
            )
            libraryDetailRoutes(
                navController = navController,
                playbackViewModel = playbackViewModel,
                chromeBottomPadding = chromeBottomPadding,
            )
        }
    }
}

private fun NavGraphBuilder.coreRoutes(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    chromeBottomPadding: Dp,
) {
    composable(Screen.Home.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
            HomeScreen()
        }
    }
    composable(Screen.Library.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
                playbackViewModel = playbackViewModel,
            )
        }
    }
    composable(Screen.Search.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
            )
        }
    }
    composable(Screen.Settings.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
            )
        }
    }
    composable(Screen.LibraryManagement.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
            LibraryManagementScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_library_management),
                        onBack = { navController.popBackStack() },
                    )
                },
                onIssuesClick = {
                    navController.navigate(Screen.Issues.route)
                },
                onRestoreClick = {
                    navController.navigate(Screen.Restore.route)
                },
            )
        }
    }
    composable(Screen.Restore.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
            RestoreScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_restore),
                        onBack = { navController.popBackStack() },
                    )
                },
            )
        }
    }
    composable(Screen.Issues.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
            IssuesScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_issues),
                        onBack = { navController.popBackStack() },
                    )
                },
            )
        }
    }
}

private fun NavGraphBuilder.libraryDetailRoutes(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    chromeBottomPadding: Dp,
) {
    composable(Screen.Songs.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
            )
        }
    }
    composable(Screen.ArtistList.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
            )
        }
    }
    composable(
        route = Screen.ArtistDetail.ROUTE,
        arguments = listOf(
            navArgument("artistId") { type = NavType.StringType },
            navArgument("artistName") { type = NavType.StringType },
        ),
    ) { entry ->
        val artistId = entry.arguments?.getString("artistId") ?: ""
        val artistName = entry.arguments?.getString("artistName") ?: ""
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
            ArtistDetailScreen(
                topBar = {
                    DetailTopBar(
                        title = artistName,
                        onBack = { navController.popBackStack() },
                    )
                },
                artistId = artistId,
                playbackViewModel = playbackViewModel,
            )
        }
    }
    composable(Screen.AlbumList.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
            )
        }
    }
    composable(
        route = Screen.AlbumDetail.ROUTE,
        arguments = listOf(
            navArgument("albumId") { type = NavType.StringType },
            navArgument("albumTitle") { type = NavType.StringType },
        ),
    ) { entry ->
        val albumId = entry.arguments?.getString("albumId") ?: ""
        val albumTitle = entry.arguments?.getString("albumTitle") ?: ""
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
            AlbumDetailScreen(
                topBar = {
                    DetailTopBar(
                        title = albumTitle,
                        onBack = { navController.popBackStack() },
                    )
                },
                albumId = albumId,
                playbackViewModel = playbackViewModel,
            )
        }
    }
    composable(Screen.PlaylistList.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
            )
        }
    }
    composable(Screen.CreatePlaylist.route) {
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
        val groupId = entry.arguments?.getString("groupId") ?: ""
        val groupName = entry.arguments?.getString("groupName") ?: ""
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
            )
        }
    }
    composable(
        route = Screen.PlaylistDetail.ROUTE,
        arguments = listOf(
            navArgument("playlistId") { type = NavType.StringType },
            navArgument("playlistName") { type = NavType.StringType },
        ),
    ) { entry ->
        val playlistId = entry.arguments?.getString("playlistId") ?: ""
        val playlistName = entry.arguments?.getString("playlistName") ?: ""
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
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
            )
        }
    }
    composable(
        route = Screen.AddSongs.ROUTE,
        arguments = listOf(
            navArgument("playlistId") { type = NavType.StringType },
        ),
    ) { entry ->
        val playlistId = entry.arguments?.getString("playlistId") ?: ""
        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
            AddSongsScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_add_songs),
                        onBack = { navController.popBackStack() },
                    )
                },
                playlistId = playlistId,
                onSubmitted = { navController.popBackStack() },
            )
        }
    }
}
