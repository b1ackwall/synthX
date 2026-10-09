package com.v1per.synthx.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.v1per.synthx.BuildConfig
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.playback.PlayerBackgroundArtRequest
import com.v1per.synthx.ui.components.BottomNavHeight
// DEAD CODE: Home and the player no longer use the playing song's palette. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.ui.components.DerivedBlobColors
import com.v1per.synthx.ui.components.next
import com.v1per.synthx.ui.player.PlayerBackgroundDebugStateHolder
import com.v1per.synthx.ui.player.PlayerSheetSkeleton
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
internal fun BottomChromeOverlay(
    navController: NavController,
    currentRoute: String?,
    showBottomBar: Boolean,
    currentSong: Song?,
    // DEAD CODE: Home and the player no longer use the playing song's palette. Delete in a cleanup pass. Ask  before deleting.
    // ambientColors: DerivedBlobColors?,
    playerProgress: Float,
    onPlayerProgressChanged: (Float) -> Unit,
    collapsePlayerSignal: Int,
    playbackViewModel: PlaybackViewModel,
    onSearchReselected: () -> Unit,
) {
    // Collected here rather than hoisted up into MainScaffold: currentPosition ticks every
    // second while a song plays, and MainScaffold's body also hosts navigation/dialog state
    // that has no reason to re-evaluate on that tick. Scoping the read to this composable
    // keeps the per-second recomposition contained to this subtree.
    val isPlaying by playbackViewModel.isPlaying.collectAsState()
    val isBuffering by playbackViewModel.isBuffering.collectAsState()
    val currentPosition by playbackViewModel.currentPosition.collectAsState()
    val duration by playbackViewModel.duration.collectAsState()

    // Both the nav and the sheet live in one Box sharing one navigationBarsPadding() call —
    // previously the sheet had no inset applied at all, so it measured its available height
    // against the raw window while the nav measured against the inset-adjusted one. On any
    // device with a non-zero system nav bar, that mismatch let the sheet's growing bounds
    // and the nav's fixed bounds genuinely overlap instead of sitting cleanly stacked.
    // Zero-sized (not removed from composition) when hidden — see the `hiddenModifier` doc
    // comment on SynthXBottomNav's own `modifier` param and PlayerSheetSkeleton's `visible`
    // param below for why: an AnimatedVisibility here (even with None transitions) fully
    // disposes its content on hide, which used to destroy and recreate both the nav and the
    // whole PlayerSheetSkeleton subtree — losing draggableState's expand/collapse position,
    // QueueModeState's queue-mode toggle and scroll position, etc. — every time the user
    // crossed into/out of a non-tab route (Settings, Library Management, Backups & Restore,
    // Restore, Developer Settings, Issues). Confirmed root cause of that state loss. A
    // `Modifier.size(0.dp)` ancestor instead forces both subtrees' measured size (and
    // therefore their hit-test area) to zero while keeping every `remember` slot alive in
    // place, since neither composable is ever removed from the tree.
    val hiddenModifier = if (showBottomBar) Modifier else Modifier.size(0.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SynthXBottomNav(
            navController = navController,
            currentRoute = currentRoute,
            // Fall back to 0f when nothing is playing so a stale progress can't
            // leave the nav slid off screen after the sheet is gone.
            playerProgress = if (currentSong != null) playerProgress else 0f,
            onSearchReselected = onSearchReselected,
            modifier = hiddenModifier,
        )

        // The player overlays everything as a sibling to the nav so it can expand upward
        // over the content — and, once expanded, over the nav area the sliding nav vacates.
        // It fills the whole Box; its own progress-driven inset keeps the collapsed mini
        // above the nav. Sharing the same navigationBarsPadding()'d Box as the nav (rather
        // than being a separate top-level sibling) is what keeps their coordinate frames —
        // and therefore their bounds — in agreement at every progress value.
        //
        // Still gated on currentSong != null via this plain `let` (unchanged from before):
        // with no song playing there is no player state worth preserving, so composing
        // nothing here in that case is fine — the state-preservation concern above is only
        // about showBottomBar, which is handled by hiddenModifier/visible below.
        currentSong?.let { song ->
            val hasPrevious by playbackViewModel.hasPrevious.collectAsState()
            val hasNext by playbackViewModel.hasNext.collectAsState()
            val upcomingQueue by playbackViewModel.upcomingQueue.collectAsState()
            val shuffleEnabled by playbackViewModel.shuffleEnabled.collectAsState()
            val repeatMode by playbackViewModel.repeatMode.collectAsState()
            // The song's identity and art URIs are resolved here, before the decode's async boundary.
            val backgroundArtRequest = PlayerBackgroundArtRequest.from(song)
            LaunchedEffect(backgroundArtRequest) {
                playbackViewModel.loadPlayerBackgroundArt(backgroundArtRequest)
            }
            val backgroundArt by playbackViewModel.playerBackgroundArt.collectAsState()
            // DEAD CODE: fed only the hidden debug readout of the measured brightness. Delete in a cleanup pass. Ask  before deleting.
            // if (BuildConfig.DEBUG) {
            //     val measured = backgroundArt?.brightness
            //     LaunchedEffect(measured) { PlayerBackgroundDebugStateHolder.measuredBrightness.value = measured }
            // }
            // Skeleton now renders mini content + expanded transport controls + full
            // queue-mode list (reorder, tap-to-play, swipe-to-remove, shuffle/repeat,
            // real MoreOptionsButton).
            PlayerSheetSkeleton(
                song = song,
                // DEAD CODE: Home and the player no longer use the playing song's palette. Delete in a cleanup pass. Ask  before deleting.
                // ambientColors = ambientColors,
                backgroundArt = backgroundArt?.image,
                backgroundBrightness = backgroundArt?.brightness,
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
                collapsePlayerSignal = collapsePlayerSignal,
                visible = showBottomBar,
                modifier = hiddenModifier,
            )
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
    onSearchReselected: () -> Unit,
    modifier: Modifier = Modifier,
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
        // `modifier` (the caller's hiddenModifier) goes first/outermost so a Modifier.size(0.dp)
        // there forces the incoming constraints to 0x0 before any of this composable's own
        // offset/height math runs — the later .height() below can only resolve within whatever
        // max height it's handed, so it can't reintroduce a nonzero size once the caller has
        // zeroed it.
        modifier = modifier
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
                            // Search root reselect focuses its existing field; other roots remain
                            // no-ops.
                            currentRoute == item.screen.route -> {
                                if (item.screen == Screen.Search) onSearchReselected()
                            }
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
