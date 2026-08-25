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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.BottomNavHeight
// Detached with the old player — see the two INTENTIONALLY DETACHED blocks below.
// import com.v1per.synthx.ui.components.next
// import com.v1per.synthx.ui.player.PlayerSheetSkeleton
import com.v1per.synthx.ui.player.v2.PlayerV2Host
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
    playerProgress: () -> Float,
    onPlayerProgressChanged: (Float) -> Unit,
    playbackViewModel: PlaybackViewModel,
) {
    // ---------------------------------------------------------------------------------
    // INTENTIONALLY DETACHED — PLAYER REBUILD IN PROGRESS
    //
    // These four reads exist solely to feed PlayerSheetSkeleton, whose call site further
    // down is commented out for the same reason. They are commented out rather than
    // deleted so the original wiring is preserved verbatim for reference/restoration:
    // re-enabling the old player is uncommenting this block, the call block below, and
    // the four import lines flagged at the top of this file — nothing else.
    //
    // Left live they would be dead StateFlow subscriptions (currentPosition ticks once a
    // second, recomposing this whole subtree for nothing) plus a fistful of
    // never-used-variable warnings.
    //
    // See docs/player-rebuild-design.md for the target architecture. The replacement
    // wired in below (PlayerV2Host) is a deliberately blank Stage 1.1 skeleton and takes
    // no playback state at all yet.
    //
    // // Collected here rather than hoisted up into MainScaffold: currentPosition ticks every
    // // second while a song plays, and MainScaffold's body also hosts navigation/dialog state
    // // that has no reason to re-evaluate on that tick. Scoping the read to this composable
    // // keeps the per-second recomposition contained to this subtree.
    // val isPlaying by playbackViewModel.isPlaying.collectAsState()
    // val isBuffering by playbackViewModel.isBuffering.collectAsState()
    // val currentPosition by playbackViewModel.currentPosition.collectAsState()
    // val duration by playbackViewModel.duration.collectAsState()
    // ---------------------------------------------------------------------------------

    // Stage 2.1: fresh, live reads feeding PlayerV2Host's real mini-bar content — not a
    // restoration of the commented-out block above, which still feeds only the old,
    // uncomposed PlayerSheetSkeleton call further down.
    val isPlaying by playbackViewModel.isPlaying.collectAsState()
    val isBuffering by playbackViewModel.isBuffering.collectAsState()

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
                //
                // Passed as a lambda rather than a Float so this composable is not
                // re-invoked on every frame of a drag — see SynthXBottomNav's own note.
                // The lambda itself only changes identity when currentSong changes
                // (per track, not per frame), so it stays a stable parameter throughout
                // a gesture and SynthXBottomNav stays skippable.
                playerProgress = { if (currentSong != null) playerProgress() else 0f },
            )
        }

        // The player overlays everything as a sibling to the nav so it can expand upward
        // over the content — and, once expanded, over the nav area the sliding nav vacates.
        // It fills the whole Box; its own progress-driven inset keeps the collapsed mini
        // above the nav. Sharing the same navigationBarsPadding()'d Box as the nav (rather
        // than being a separate top-level sibling) is what keeps their coordinate frames —
        // and therefore their bounds — in agreement at every progress value.
        //
        // As of Stage 1.2 this describes PlayerV2Host too, not just the detached
        // implementation: it reports progress via onProgressChanged and applies the same
        // lerp(BottomNavHeight, 0.dp, progress) bottom inset, so the nav slides and the
        // collapsed bar stacks above it. The one behavioural difference left versus the old
        // player is that the nav consumes progress through layout/draw-phase modifiers
        // rather than recomposing — visually identical, materially cheaper.
        AnimatedVisibility(
            visible = showBottomBar && currentSong != null,
            enter = ChromeEnterTransition,
            exit = ChromeExitTransition,
        ) {
            // -----------------------------------------------------------------------------
            // INTENTIONALLY DETACHED — PLAYER REBUILD IN PROGRESS
            //
            // The old player (PlayerSheetSkeleton and every composable it pulls in from
            // ui/player/ and ui/components/) is being replaced. Its call site is commented
            // out rather than deleted, and not one file of the old implementation has been
            // touched, renamed, or removed — the whole thing remains on disk, compiling, and
            // fully functional. Restoring it is: delete the replacement call below,
            // uncomment this block, uncomment the four collectAsState reads at the top of
            // BottomChromeOverlay, and uncomment the four imports flagged at the top of
            // this file. No other edit is required.
            //
            // Target architecture for the rebuild: docs/player-rebuild-design.md.
            // What replaces this today is Stage 1.1 only — a deliberately blank two-state
            // skeleton (PlayerV2Host) with no content, no playback wiring, and no chrome
            // coordination. It is NOT an attempt at the design doc's full architecture.
            //
            // currentSong?.let { song ->
            //     val hasPrevious by playbackViewModel.hasPrevious.collectAsState()
            //     val hasNext by playbackViewModel.hasNext.collectAsState()
            //     val upcomingQueue by playbackViewModel.upcomingQueue.collectAsState()
            //     val shuffleEnabled by playbackViewModel.shuffleEnabled.collectAsState()
            //     val repeatMode by playbackViewModel.repeatMode.collectAsState()
            //     // Skeleton now renders mini content + expanded transport controls + full
            //     // queue-mode list (reorder, tap-to-play, swipe-to-remove, shuffle/repeat,
            //     // real MoreOptionsButton).
            //     PlayerSheetSkeleton(
            //         song = song,
            //         isPlaying = isPlaying,
            //         isBuffering = isBuffering,
            //         currentPosition = currentPosition,
            //         duration = duration,
            //         onPlayPauseClick = playbackViewModel::togglePlayPause,
            //         onSeek = playbackViewModel::seekTo,
            //         onPreviousClick = playbackViewModel::previous,
            //         onNextClick = playbackViewModel::next,
            //         hasPrevious = hasPrevious,
            //         hasNext = hasNext,
            //         upcomingQueue = upcomingQueue,
            //         shuffleEnabled = shuffleEnabled,
            //         repeatMode = repeatMode,
            //         onShuffleClick = { playbackViewModel.setShuffleEnabled(!shuffleEnabled) },
            //         onRepeatClick = { playbackViewModel.setRepeatMode(repeatMode.next()) },
            //         onQueueSongClick = playbackViewModel::playQueueItem,
            //         onQueueMove = playbackViewModel::moveQueueItem,
            //         onQueueRemove = playbackViewModel::removeQueueItem,
            //         onProgressChanged = onPlayerProgressChanged,
            //     )
            // }
            // -----------------------------------------------------------------------------

            // Stage 1.1 replacement, in the old call's exact position and under the exact
            // same visibility gating (showBottomBar && currentSong != null, above).
            //
            // Stage 1.2 wires the one thing it produced then: onProgressChanged closes the
            // loop back to MainScaffold's playerProgress state, which flows down the
            // playerProgress lambda above and drives the nav's slide/shrink/fade. The sheet
            // applies the complementary bottom inset internally, so the collapsed bar now
            // stacks above the nav instead of overlaying it.
            //
            // Stage 2.1 adds real mini-bar content, so song/isPlaying/isBuffering/
            // onPlayPauseClick are now real too. currentSong?.let is redundant with the
            // AnimatedVisibility gate above (currentSong is never null once this content is
            // entered) but matches the old block's own currentSong?.let pattern above rather
            // than introducing a differently-shaped non-null assertion here.
            currentSong?.let { song ->
                PlayerV2Host(
                    song = song,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onPlayPauseClick = playbackViewModel::togglePlayPause,
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
    playerProgress: () -> Float = { 0f },
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
    // All three collapse properties read playerProgress() inside a lambda, so the read
    // happens in the layout or draw phase rather than in composition. This matters now that
    // Stage 1.2 feeds a real per-frame value: as a plain Float parameter, every frame of a
    // drag would be a *changed parameter*, which makes this composable unskippable and
    // re-runs the whole body — the items list, three NavigationBarItems, three
    // stringResource lookups — 60 times a second. As a lambda the parameter's identity is
    // stable for the whole gesture, nothing here subscribes to the value in composition, and
    // the snapshot read lands in the phase that actually consumes it. Progress changes then
    // invalidate layout and draw only. See docs/player-rebuild-design.md §5.
    Column(
        modifier = Modifier
            // Lambda overload of offset (Density.() -> IntOffset), not offset(y: Dp): same
            // downward slide by the full BottomNavHeight across the drag, resolved during
            // placement.
            .offset {
                val collapseFraction = playerProgress().coerceIn(0f, 1f)
                IntOffset(0, (BottomNavHeight * collapseFraction).roundToPx())
            }
            // Layout-phase equivalent of .height(BottomNavHeight * (1f - collapseFraction)).
            // Modifier.height pins min and max height to the same value and measures the
            // child against that, so the fixed 70dp NavigationBar + 1dp divider inside are
            // squeezed as the container shrinks; measuring with minHeight = maxHeight = the
            // target reproduces that byte-for-byte, just one phase later.
            .layout { measurable, constraints ->
                val collapseFraction = playerProgress().coerceIn(0f, 1f)
                val targetHeight = (BottomNavHeight * (1f - collapseFraction))
                    .roundToPx()
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(
                    constraints.copy(minHeight = targetHeight, maxHeight = targetHeight),
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .clipToBounds()
            // Already a draw-phase lambda, but it previously closed over a value computed in
            // composition; reading playerProgress() here instead makes the deferral real.
            .graphicsLayer { alpha = 1f - playerProgress().coerceIn(0f, 1f) },
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
