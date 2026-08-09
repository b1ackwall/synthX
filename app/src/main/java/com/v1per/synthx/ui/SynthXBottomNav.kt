package com.v1per.synthx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import com.v1per.synthx.ui.components.PlayerSheet
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun BottomChromeOverlay(
    navController: NavController,
    currentRoute: String?,
    showBottomBar: Boolean,
    previousRoute: String?,
    targetHomeTransition: Boolean,
    currentSong: Song?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    playerProgress: Float,
    onPlayerProgressChanged: (Float) -> Unit,
    onQueueClick: () -> Unit,
    playbackViewModel: PlaybackViewModel,
) {
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
                onQueueClick = onQueueClick,
                onProgressChanged = onPlayerProgressChanged,
            )
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
        ),
        BottomNavItem(
            Screen.Search,
            stringResource(R.string.nav_search),
            Icons.Filled.Search,
            Icons.Outlined.Search,
        ),
    )

    // Slides the whole bar (divider + nav) down by its 70dp height as the player expands,
    // clearing the way for the sheet to cover the full screen at progress = 1f.
    Column(
        modifier = Modifier.offset(y = (playerProgress * 70).dp),
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
)
