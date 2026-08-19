package com.v1per.synthx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.next
import com.v1per.synthx.ui.player.PlayerSheetSkeleton
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.TextSecondary

// Shared by both overlay AnimatedVisibility blocks below — instant cut, matching the
// app-wide convention (the only animated transition anywhere is mini-player↔full-player).
private val ChromeEnterTransition = EnterTransition.None
private val ChromeExitTransition = ExitTransition.None

@Composable
fun BottomChromeOverlay(
    navController: NavController,
    currentRoute: String?,
    showBottomBar: Boolean,
    currentSong: Song?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    playerProgress: Float,
    onPlayerProgressChanged: (Float) -> Unit,
    playbackViewModel: PlaybackViewModel,
) {
    // Both the nav and the sheet live in one Box sharing one navigationBarsPadding() call —
    // previously the sheet had no inset applied at all, so it measured its available height
    // against the raw window while the nav measured against the inset-adjusted one. On any
    // device with a non-zero system nav bar, that mismatch let the sheet's growing bounds
    // and the nav's fixed bounds genuinely overlap instead of sitting cleanly stacked.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = showBottomBar,
            enter = ChromeEnterTransition,
            exit = ChromeExitTransition,
        ) {
            SynthXBottomNav(
                navController = navController,
                currentRoute = currentRoute,
                // Fall back to 0f when nothing is playing so a stale progress can't
                // leave the nav slid off screen after the sheet is gone.
                playerProgress = if (currentSong != null) playerProgress else 0f,
            )
        }

        // The player overlays everything as a sibling to the nav so it can expand upward
        // over the content — and, once expanded, over the nav area the sliding nav vacates.
        // It fills the whole Box; its own progress-driven inset keeps the collapsed mini
        // above the nav. Sharing the same navigationBarsPadding()'d Box as the nav (rather
        // than being a separate top-level sibling) is what keeps their coordinate frames —
        // and therefore their bounds — in agreement at every progress value.
        AnimatedVisibility(
            visible = showBottomBar && currentSong != null,
            enter = ChromeEnterTransition,
            exit = ChromeExitTransition,
        ) {
            currentSong?.let { song ->
                val hasPrevious by playbackViewModel.hasPrevious.collectAsState()
                val hasNext by playbackViewModel.hasNext.collectAsState()
                val upcomingQueue by playbackViewModel.upcomingQueue.collectAsState()
                val shuffleEnabled by playbackViewModel.shuffleEnabled.collectAsState()
                val repeatMode by playbackViewModel.repeatMode.collectAsState()
                // Skeleton now renders mini content + expanded transport controls + full
                // queue-mode list (reorder, tap-to-play, swipe-to-remove, shuffle/repeat,
                // real MoreOptionsButton).
                PlayerSheetSkeleton(
                    song = song,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPlayPauseClick = playbackViewModel::togglePlayPause,
                    onSeek = playbackViewModel::seekTo,
                    onPreviousClick = playbackViewModel::previous,
                    onNextClick = playbackViewModel::next,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    upcomingQueue = upcomingQueue,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onShuffleClick = { playbackViewModel.setShuffleEnabled(!shuffleEnabled) },
                    onRepeatClick = { playbackViewModel.setRepeatMode(repeatMode.next()) },
                    onQueueSongClick = playbackViewModel::playQueueItem,
                    onQueueMove = playbackViewModel::moveQueueItem,
                    onQueueRemove = playbackViewModel::removeQueueItem,
                    onProgressChanged = onPlayerProgressChanged,
                )
            }
        }
    }
}

// Tapping the Library tab from any of these should reset to Library's root rather than
// restore mid-stack. See Screen.libraryDetailRoutes.
private val libraryTabRoutes = Screen.libraryDetailRoutes

@Composable
private fun SynthXBottomNav(
    navController: NavController,
    currentRoute: String?,
    playerProgress: Float = 0f,
) {
    val items = listOf(
        BottomNavItem(
            Screen.Home,
            stringResource(R.string.nav_home),
            Icons.Filled.Home,
            Icons.Outlined.Home,
        ),
        BottomNavItem(
            Screen.Library,
            stringResource(R.string.nav_library),
            Icons.Filled.LibraryMusic,
            Icons.Outlined.LibraryMusic,
            hierarchyRoutes = libraryTabRoutes,
        ),
        BottomNavItem(
            Screen.Search,
            stringResource(R.string.nav_search),
            Icons.Filled.Search,
            Icons.Outlined.Search,
        ),
    )

    // Slides the whole bar (divider + nav) down by its full BottomNavHeight as the player
    // expands, clearing the way for the sheet to cover the full screen at progress = 1f.
    // Shares BottomNavHeight with PlayerSheet's bottomInset lerp rather than a separate
    // hardcoded constant — a mismatch here previously let the sheet's bottom edge and the
    // nav's sliding top edge drift apart by up to 1dp across the drag.
    //
    // The offset alone isn't enough: this whole overlay sits inside enableEdgeToEdge()'s
    // transparent system bars (MainActivity), so at progress = 1f the offset pushes the bar
    // into the reserved system-nav-bar inset rather than behind opaque system chrome — and
    // Compose doesn't clip a Box/Column's children to its bounds by default, so that offset
    // content stays visible as a sliver instead of being hidden. Shrinking height to 0 and
    // clipToBounds() (so the fixed-height NavigationBar/divider inside can't paint past a
    // zero-height container) plus a matching alpha fade are the actual fix; the offset alone
    // was the previous, insufficient attempt.
    val collapseFraction = playerProgress.coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .offset(y = BottomNavHeight * collapseFraction)
            .height(BottomNavHeight * (1f - collapseFraction))
            .clipToBounds()
            .graphicsLayer { alpha = 1f - collapseFraction },
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
        NavigationBar(
            containerColor = Surface,
            tonalElevation = 0.dp,
            modifier = Modifier.height(70.dp),
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.screen.route
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        when {
                            // Already at this tab's own root — nothing to do.
                            currentRoute == item.screen.route -> Unit
                            // Deeper in this tab's own hierarchy — reset to root instead of
                            // restoring the sub-screen just left.
                            currentRoute in item.hierarchyRoutes -> {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = false
                                    }
                                    launchSingleTop = true
                                }
                            }
                            // A different tab entirely — standard restore-on-switch.
                            else -> {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    },
                    icon = {
                        // Sized up from Material3's 24dp default to 28dp for visual weight
                        // parity with the wordmark/accent elements elsewhere in the app. No
                        // manual y-offset: NavigationBarItem already centers the icon above
                        // the label itself, and a prior offset(y = 6dp) here pushed the icon
                        // down far enough to read as unbalanced against the label on-device.
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            modifier = Modifier.size(28.dp),
                        )
                    },
                    label = {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        unselectedIconColor = TextSecondary,
                        selectedTextColor = Accent,
                        unselectedTextColor = TextSecondary,
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
    val hierarchyRoutes: Set<String> = emptySet(),
)
