package com.v1per.synthx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.components.PlayerSheet
import com.v1per.synthx.ui.components.SynthXTopBar
import com.v1per.synthx.ui.screens.addsongs.AddSongsScreen
import com.v1per.synthx.ui.screens.albumdetail.AlbumDetailScreen
import com.v1per.synthx.ui.screens.albumlist.AlbumListScreen
import com.v1per.synthx.ui.screens.artistdetail.ArtistDetailScreen
import com.v1per.synthx.ui.screens.artistlist.ArtistListScreen
import com.v1per.synthx.ui.screens.createplaylist.CreatePlaylistScreen
import com.v1per.synthx.ui.screens.groupdetail.GroupDetailScreen
import com.v1per.synthx.ui.screens.home.HomeScreen
import com.v1per.synthx.ui.screens.playlistdetail.PlaylistDetailScreen
import com.v1per.synthx.ui.screens.playlistlist.PlaylistListScreen
import com.v1per.synthx.ui.screens.library.LibraryScreen
import com.v1per.synthx.ui.screens.search.SearchScreen
import com.v1per.synthx.ui.screens.settings.SettingsScreen
import com.v1per.synthx.ui.screens.songs.SongsScreen
import com.v1per.synthx.ui.theme.Motion

/** Footprint of the overlay bottom nav (70dp NavigationBar + 1dp divider). */
private val BottomNavHeight = 71.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainScaffold(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Home is a full-screen takeover: no bottom nav, no PlayerSheet.
    val bottomBarRoutes = setOf(
        Screen.Library.route,
        Screen.Search.route,
        Screen.Songs.route,
        Screen.ArtistList.route,
        Screen.ArtistDetail.ROUTE,
        Screen.AlbumList.route,
        Screen.AlbumDetail.ROUTE,
        Screen.PlaylistList.route,
        Screen.CreatePlaylist.route,
        Screen.GroupDetail.ROUTE,
        Screen.PlaylistDetail.ROUTE,
        Screen.AddSongs.ROUTE,
    )
    val showBottomBar = currentRoute in bottomBarRoutes

    val playbackViewModel = viewModel<PlaybackViewModel>()
    val isPlaying by playbackViewModel.isPlaying.collectAsState()
    val currentSong by playbackViewModel.currentSong.collectAsState()
    val currentPosition by playbackViewModel.currentPosition.collectAsState()
    val duration by playbackViewModel.duration.collectAsState()

    // 0f = collapsed mini player, 1f = fully expanded. Reported up by PlayerSheet and used
    // to slide the bottom nav down in lockstep with the sheet's expansion.
    var playerProgress by remember { mutableStateOf(0f) }

    // The route we were on before the current one. The assignment runs in a LaunchedEffect,
    // i.e. after the composition that observed the route change — so during the frame where
    // showBottomBar flips, this still holds the outgoing route.
    var previousRoute by remember { mutableStateOf<String?>(null) }

    // True when we're leaving a bottom-bar route for Home — the one departure that should
    // fade the chrome out instead of cutting it. Derived during composition, not in the
    // effect, so it's already correct on the frame showBottomBar flips false.
    val targetHomeTransition = previousRoute in bottomBarRoutes &&
        currentRoute == Screen.Home.route

    LaunchedEffect(currentRoute) {
        previousRoute = currentRoute
    }

    // Bottom chrome reservation, applied per destination (never on the NavHost itself, so
    // an outgoing screen can't reflow mid-transition; Home gets no wrapper at all).
    val chromeBottomPadding by remember(currentSong, showBottomBar) {
        derivedStateOf {
            when {
                !showBottomBar -> 0.dp
                currentSong != null -> BottomNavHeight + CollapsedPlayerHeight
                else -> BottomNavHeight
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {},
            bottomBar = {},
        ) { innerPadding ->
            SharedTransitionLayout {
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
                    composable(
                        route = Screen.Home.route,
                        // Forward is an instant cut (the shared-element wordmark carries the
                        // motion); popping back crossfades Home in over Library's fadeOut.
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = {
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = Motion.DurationNormal,
                                    easing = Motion.EasingDecelerate,
                                ),
                            )
                        },
                    ) {
                        HomeScreen(
                            onEnterLibrary = {
                                navController.navigate(Screen.Library.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
                        )
                    }
                    composable(
                        route = Screen.Library.route,
                        // Only the Home boundary is animated. Coming from Home, Library fades
                        // in after the gradient's 300ms fade-to-black completes (the wordmark
                        // shared element overlaps this fade, so it's already in position as
                        // Library appears); popping to Home fades out under Home's fadeIn.
                        // Every other neighbour (Search tab, etc.) is an instant cut.
                        enterTransition = {
                            if (initialState.destination.route == Screen.Home.route) {
                                fadeIn(
                                    animationSpec = tween(
                                        durationMillis = Motion.DurationNormal,
                                        easing = Motion.EasingDecelerate,
                                        delayMillis = Motion.DurationNormal,
                                    ),
                                )
                            } else {
                                EnterTransition.None
                            }
                        },
                        popExitTransition = {
                            if (targetState.destination.route == Screen.Home.route) {
                                fadeOut(
                                    animationSpec = tween(
                                        durationMillis = Motion.DurationNormal,
                                        easing = Motion.EasingAccelerate,
                                    ),
                                )
                            } else {
                                ExitTransition.None
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
                            LibraryScreen(
                                topBar = {
                                    SynthXTopBar(
                                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = this@composable,
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
                                        title = "Settings",
                                        onBack = { navController.popBackStack() },
                                    )
                                },
                            )
                        }
                    }
                    composable(Screen.Songs.route) {
                        Box(Modifier.fillMaxSize().padding(bottom = chromeBottomPadding)) {
                            SongsScreen(
                                topBar = { sortOrder, onSortClick ->
                                    DetailTopBar(
                                        title = "Songs",
                                        onBack = { navController.popBackStack() },
                                        actions = {
                                            IconButton(onClick = onSortClick) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                                    contentDescription = "Sort",
                                                    tint = MaterialTheme.colorScheme.onBackground,
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
                                        title = "Artists",
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
                                        title = "Albums",
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
                                        title = "Playlists",
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
                                        title = "New Playlist",
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
                                        title = "Add Songs",
                                        onBack = { navController.popBackStack() },
                                    )
                                },
                                playlistId = playlistId,
                                onSubmitted = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }

        // Bottom nav as overlay — never affects layout. Arriving from Home it fades in on
        // Library's delayed schedule (300ms delay + 300ms fade); every other arrival —
        // Settings back included — is instant. Leaving to Home it fades out with Library's
        // pop easing; every other departure is instant. Always composed so AnimatedVisibility
        // can actually run the animations on the visibility flip instead of popping.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = if (previousRoute == Screen.Home.route) {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = Motion.DurationNormal,
                            easing = Motion.EasingDecelerate,
                            delayMillis = Motion.DurationNormal,
                        ),
                    )
                } else {
                    EnterTransition.None
                },
                exit = if (targetHomeTransition) {
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = Motion.DurationNormal,
                            easing = Motion.EasingAccelerate,
                        ),
                    )
                } else {
                    ExitTransition.None
                },
            ) {
                SynthXBottomNav(
                    navController = navController,
                    currentRoute = currentRoute,
                    // Fall back to 0f when nothing is playing so a stale progress can't
                    // leave the nav slid off screen after the sheet is gone.
                    playerProgress = if (currentSong != null) playerProgress else 0f,
                )
            }
        }

        // The player overlays everything as a sibling to the Scaffold so it can expand
        // upward over the content — and, once expanded, over the nav area the sliding nav
        // vacates. It fills the whole Box; its own progress-driven inset keeps the collapsed
        // mini above the nav. Runs the same schedule as the bottom nav: a delayed fade when
        // arriving from Home, a fade out when leaving to Home, instant everywhere else.
        AnimatedVisibility(
            visible = showBottomBar && currentSong != null,
            enter = if (previousRoute == Screen.Home.route) {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = Motion.DurationNormal,
                        easing = Motion.EasingDecelerate,
                        delayMillis = Motion.DurationNormal,
                    ),
                )
            } else {
                EnterTransition.None
            },
            exit = if (targetHomeTransition) {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = Motion.DurationNormal,
                        easing = Motion.EasingAccelerate,
                    ),
                )
            } else {
                ExitTransition.None
            },
        ) {
            currentSong?.let { song ->
                PlayerSheet(
                    song = song,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPlayPauseClick = playbackViewModel::togglePlayPause,
                    onSeek = playbackViewModel::seekTo,
                    onProgressChanged = { playerProgress = it },
                )
            }
        }
    }
}

@Composable
private fun SynthXBottomNav(
    navController: NavController,
    currentRoute: String?,
    playerProgress: Float = 0f,
) {
    val items = listOf(
        BottomNavItem(Screen.Home, "Home", Icons.Filled.Home, Icons.Outlined.Home),
        BottomNavItem(Screen.Library, "Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
        BottomNavItem(Screen.Search, "Search", Icons.Filled.Search, Icons.Outlined.Search),
    )

    // Slides the whole bar (divider + nav) down by its 70dp height as the player expands,
    // clearing the way for the sheet to cover the full screen at progress = 1f.
    Column(
        modifier = Modifier.offset(y = (playerProgress * 70).dp),
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.height(70.dp),
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.screen.route
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        when {
                            item.screen.route == Screen.Home.route -> {
                                // Home is only ever reached as a pop from Library, so that the
                                // Home/Library crossfade pair runs. From anywhere deeper, land
                                // on Library first — a second tap then pops through to Home.
                                if (currentRoute == Screen.Library.route) {
                                    navController.popBackStack(Screen.Home.route, inclusive = false)
                                } else {
                                    navController.navigate(Screen.Library.route) {
                                        popUpTo(Screen.Home.route) { inclusive = false }
                                        restoreState = true
                                    }
                                }
                            }
                            currentRoute in setOf(
                                Screen.Songs.route,
                                Screen.ArtistList.route,
                                Screen.ArtistDetail.ROUTE,
                                Screen.AlbumList.route,
                                Screen.AlbumDetail.ROUTE,
                                Screen.PlaylistList.route,
                                Screen.CreatePlaylist.route,
                                Screen.GroupDetail.ROUTE,
                                Screen.PlaylistDetail.ROUTE,
                                Screen.AddSongs.ROUTE,
                            ) && item.screen.route == Screen.Library.route -> {
                                navController.popBackStack(Screen.Library.route, inclusive = false)
                            }
                            !selected -> {
                                navController.navigate(item.screen.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            modifier = Modifier.size(28.dp).offset(y = 6.dp),
                        )
                    },
                    label = {
                        Text(
                            text = item.label,
                            fontSize = 14.sp,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

private data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)
