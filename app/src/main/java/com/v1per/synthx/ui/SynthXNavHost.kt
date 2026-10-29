package com.v1per.synthx.ui

import android.annotation.SuppressLint
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.tracing.Trace
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTraceSections
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.screens.songs.SortOrder
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.TextPrimary
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

// Body is bracketed by begin/endSection (PerfTraceSections); try/finally cannot wrap composables.
@SuppressLint("UnclosedTrace")
@Composable
internal fun SynthXNavHost(
    navController: NavHostController,
    playbackViewModel: PlaybackViewModel,
    chromeBottomPadding: Dp,
    searchReselectEvents: SharedFlow<Unit>,
    // Rendering lives at MainScaffold level (above BottomChromeOverlay) so the sheet draws
    // above the bottom nav/player as a structural sibling, not a descendant of this NavHost.
    onShowSortSheet: (currentOrder: SortOrder, onOrderSelected: (SortOrder) -> Unit) -> Unit,
    // Same arrangement, same reason, for the playlist-group picker: the screen supplies the
    // groups, the playlist's current group (null when ungrouped) and what to do with the pick.
    onShowGroupPicker: (
        groups: List<PlaylistGroup>,
        currentGroupId: String?,
        onGroupSelected: (groupId: String) -> Unit,
    ) -> Unit,
    // Resolved once in MainActivity by shouldShowOnboarding (onboarding not complete, or a
    // first-scan request pending) and threaded down through MainScaffold/NormalShell —
    // Screen.Onboarding.route when it is true, Screen.Home.route otherwise.
    startDestination: String = Screen.Home.route,
    onRestoreTerminalShown: (UUID) -> Unit = {},
) {
    Trace.beginSection(PerfTraceSections.NavHost)
    Scaffold(
        containerColor = Background,
        contentColor = TextPrimary,
        topBar = {},
        bottomBar = {},
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .testTag(ShellTestTags.NavHost)
                .padding(innerPadding)
                // Screens render their own TopAppBars — consume the insets this
                // padding already covers so those bars don't re-apply the status inset.
                .consumeWindowInsets(innerPadding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            // The predictive pair does not inherit the pop pair; its defaults scale and fade the screen.
            predictivePopEnterTransition = { EnterTransition.None },
            predictivePopExitTransition = { ExitTransition.None },
        ) {
            onboardingRoutes(
                navController = navController,
                onRestoreTerminalShown = onRestoreTerminalShown,
            )
            tabRoutes(
                navController = navController,
                playbackViewModel = playbackViewModel,
                chromeBottomPadding = chromeBottomPadding,
                searchReselectEvents = searchReselectEvents,
            )
            libraryManagementRoutes(
                navController = navController,
                chromeBottomPadding = chromeBottomPadding,
            )
            backupsAndRestoreRoutes(
                navController = navController,
                chromeBottomPadding = chromeBottomPadding,
                onRestoreTerminalShown = onRestoreTerminalShown,
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
                onShowGroupPicker = onShowGroupPicker,
            )
        }
    }
    Trace.endSection()
}
