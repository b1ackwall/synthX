package com.v1per.synthx.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.v1per.synthx.R
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.PlayerSheet
import com.v1per.synthx.ui.screens.home.HomeScreen
import com.v1per.synthx.ui.screens.library.LibraryScreen
import com.v1per.synthx.ui.screens.search.SearchScreen
import com.v1per.synthx.ui.screens.settings.SettingsScreen
import com.v1per.synthx.ui.screens.songs.SongsScreen

@Composable
fun MainScaffold(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val mainRoutes = setOf(
        Screen.Home.route,
        Screen.Library.route,
        Screen.Search.route,
    )
    val bottomBarRoutes = setOf(
        Screen.Home.route,
        Screen.Library.route,
        Screen.Search.route,
        Screen.Songs.route,
    )
    val isMainRoute = currentRoute == null || currentRoute in mainRoutes
    val showBottomBar = currentRoute == null || currentRoute in bottomBarRoutes

    val playbackViewModel = viewModel<PlaybackViewModel>()
    val isPlaying by playbackViewModel.isPlaying.collectAsState()
    val currentSong by playbackViewModel.currentSong.collectAsState()

    // 0f = collapsed mini player, 1f = fully expanded. Reported up by PlayerSheet and used
    // to slide the bottom nav down in lockstep with the sheet's expansion.
    var playerProgress by remember { mutableStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                when {
                    isMainRoute -> SynthXTopBar(
                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    )
                    currentRoute == Screen.Settings.route -> DetailTopBar(
                        title = "Settings",
                        onBack = { navController.popBackStack() },
                    )
                    currentRoute == Screen.Songs.route -> DetailTopBar(
                        title = "Songs",
                        onBack = { navController.popBackStack() },
                        actions = {
                            IconButton(onClick = {}) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Sort",
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        },
                    )
                }
            },
            bottomBar = {
                if (showBottomBar) {
                    SynthXBottomNav(
                        navController = navController,
                        currentRoute = currentRoute,
                        // Fall back to 0f when nothing is playing so a stale progress can't
                        // leave the nav slid off screen after the sheet is gone.
                        playerProgress = if (currentSong != null) playerProgress else 0f,
                    )
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Reserve space for the collapsed mini player that floats above the nav bar.
                    .padding(bottom = if (currentSong != null) CollapsedPlayerHeight else 0.dp),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Screen.Home.route) { HomeScreen() }
                composable(Screen.Library.route) {
                    LibraryScreen(
                        onNavigateToSongs = { navController.navigate(Screen.Songs.route) },
                    )
                }
                composable(Screen.Search.route) { SearchScreen() }
                composable(Screen.Settings.route) { SettingsScreen() }
                composable(Screen.Songs.route) {
                    SongsScreen(playbackViewModel = playbackViewModel)
                }
            }
        }

        // The player overlays everything as a sibling to the Scaffold so it can expand
        // upward over the content — and, once expanded, over the nav area the sliding nav
        // vacates. It fills the whole Box; its own progress-driven inset keeps the collapsed
        // mini above the nav.
        if (showBottomBar) {
            currentSong?.let { song ->
                PlayerSheet(
                    song = song,
                    isPlaying = isPlaying,
                    onPlayPauseClick = playbackViewModel::togglePlayPause,
                    onProgressChanged = { playerProgress = it },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SynthXTopBar(onSettingsClick: () -> Unit) {
    TopAppBar(
        title = {
            val wordmarkFont = FontFamily(Font(R.font.bitcount_grid_double_regular, FontWeight.Normal))

            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.onBackground,
                            fontFamily = wordmarkFont,
                        ),
                    ) {
                        append("synth")
                    }
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = wordmarkFont,
                        ),
                    ) {
                        append("X")
                    }
                },
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        actions = {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
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
                        if (currentRoute == Screen.Songs.route && item.screen.route == Screen.Library.route) {
                            navController.popBackStack()
                        } else if (!selected) {
                            navController.navigate(item.screen.route) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
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
