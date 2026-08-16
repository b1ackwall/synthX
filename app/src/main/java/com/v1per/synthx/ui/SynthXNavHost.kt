package com.v1per.synthx.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.screens.songs.SortOrder
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary

@Composable
fun SynthXNavHost(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    chromeBottomPadding: Dp,
    // Rendering lives at MainScaffold level (above BottomChromeOverlay) so the sheet draws
    // above the bottom nav/player as a structural sibling, not a descendant of this NavHost.
    onShowSortSheet: (currentOrder: SortOrder, onOrderSelected: (SortOrder) -> Unit) -> Unit,
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
            tabRoutes(
                navController = navController,
                playbackViewModel = playbackViewModel,
                chromeBottomPadding = chromeBottomPadding,
            )
            libraryManagementRoutes(
                navController = navController,
                chromeBottomPadding = chromeBottomPadding,
            )
            browseRoutes(
                navController = navController,
                playbackViewModel = playbackViewModel,
                chromeBottomPadding = chromeBottomPadding,
                onShowSortSheet = onShowSortSheet,
            )
            playlistRoutes(
                navController = navController,
                playbackViewModel = playbackViewModel,
                chromeBottomPadding = chromeBottomPadding,
            )
        }
    }
}
