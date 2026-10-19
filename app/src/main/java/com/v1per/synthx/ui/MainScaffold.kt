package com.v1per.synthx.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tracing.Trace
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.perf.PerfTraceSections
import com.v1per.synthx.playback.PlaybackError
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.rememberNotificationPermissionGate
import com.v1per.synthx.playback.QueuePromptEvent
import com.v1per.synthx.playback.QueueReplacePromptEvent
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.GroupPickerBottomSheet
import com.v1per.synthx.ui.components.QueuePromptDialog
import com.v1per.synthx.ui.components.QueueReplaceDialog
import com.v1per.synthx.ui.components.hiddenFromAccessibilityWhileSheetShown
import com.v1per.synthx.ui.components.SongActionsHost
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.screens.songs.SortBottomSheet
import com.v1per.synthx.ui.screens.songs.SortOrder
import com.v1per.synthx.ui.screens.restore.RestoreAppStatus
import com.v1per.synthx.ui.screens.restore.RestoreStatusViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * The app shell. Which of three surfaces it composes is decided by [RestoreShellPolicy] from
 * restore's two lifecycle signals — see that file for why watching [RestoreCoordinator.terminal]
 * alone is not enough.
 *
 * [NormalShell] is a separate composable rather than an inline `else` branch so that entering
 * maintenance genuinely *disposes* the nav graph, its ViewModels and every Room Flow they
 * collect, instead of merely drawing something over a still-live tree.
 */
@Composable
fun MainScaffold(
    navController: NavHostController = rememberNavController(),
    // Resolved once in MainActivity from PreferencesManager.onboardingComplete — see
    // SynthXNavHost's startDestination parameter for where this ends up.
    startDestination: String = Screen.Home.route,
    restoreStatusViewModel: RestoreStatusViewModel? = null,
) {
    val restoreMode by RestoreCoordinator.mode.collectAsState()
    val restoreTerminal by RestoreCoordinator.terminal.collectAsState()
    val diagnosticOperationId by RestoreCoordinator.diagnosticOperationId.collectAsState()

    when (RestoreShellPolicy.decide(restoreMode, restoreTerminal)) {
        RestoreShellSurface.Maintenance -> RestoreMaintenanceSurface()

        RestoreShellSurface.Terminal -> RestoreTerminalSurface(restoreTerminal, diagnosticOperationId)

        RestoreShellSurface.Normal -> NormalShell(
            navController,
            startDestination,
            restoreStatusViewModel,
        )
    }
}

/**
 * Second chance: the first time playback actually runs, offer the notification
 * permission if neither the wizard's Done nor a restore has already done so (the shared "asked"
 * flag decides). Lives in the UI layer, observing [PlaybackViewModel.isPlaying], because a
 * permission prompt needs an Activity and [com.v1per.synthx.playback.SynthXPlaybackService]
 * has none. Collected in its own composable so a play/pause toggle doesn't recompose the shell.
 * Waits for RESUMED: playback can start from a headset button or the media notification while
 * the app is in the background, where a prompt would have nowhere to appear.
 */
@Composable
private fun NotificationSecondChanceEffect(playbackViewModel: PlaybackViewModel) {
    val gate = rememberNotificationPermissionGate()
    val isPlaying by playbackViewModel.isPlaying.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isPlaying, gate) {
        if (!isPlaying) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            gate.runAfterRationale {}
        }
    }
}

// Body is bracketed by begin/endSection (PerfTraceSections); try/finally cannot wrap composables.
@SuppressLint("UnclosedTrace")
@Composable
private fun NormalShell(
    navController: NavHostController,
    startDestination: String,
    restoreStatusViewModel: RestoreStatusViewModel?,
) {
    Trace.beginSection(PerfTraceSections.MainShell)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val restoreStatus = if (restoreStatusViewModel == null) {
        RestoreAppStatus.Idle
    } else {
        val value by restoreStatusViewModel.status.collectAsState()
        value
    }

    // Home/Library/Search plus every route in Library's own detail hierarchy — see
    // Screen.libraryDetailRoutes, also shared by SynthXBottomNav.
    val bottomBarRoutes = setOf(
        Screen.Home.route,
        Screen.Library.route,
        Screen.Search.route,
    ) + Screen.libraryDetailRoutes
    val showBottomBar = currentRoute in bottomBarRoutes

    val context = LocalContext.current
    val resources = LocalResources.current
    val playbackViewModel = viewModel<PlaybackViewModel>(
        factory = PlaybackViewModel.Factory(context.applicationContext),
    )
    NotificationSecondChanceEffect(playbackViewModel)
    val snackbarHostState = remember { SnackbarHostState() }
    val searchReselectEvents = remember {
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    // resources.getString rather than stringResource: the collect lambda is not a composable
    // scope, and the message needs the song title formatted in at emit time.
    LaunchedEffect(Unit) {
        playbackViewModel.errors.collect { error ->
            val message = when (error) {
                is PlaybackError.PlaybackFailed ->
                    resources.getString(R.string.playback_error_failed, error.songTitle)

                PlaybackError.ServiceUnavailable ->
                    resources.getString(R.string.playback_error_service_unavailable)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // Held as state rather than shown from inside the collector: the event fires once, but
    // the dialog has to stay up until the user answers it.
    var queuePrompt by remember { mutableStateOf<QueuePromptEvent?>(null) }
    LaunchedEffect(Unit) {
        playbackViewModel.queuePrompts.collect { queuePrompt = it }
    }
    var queueReplacePrompt by remember { mutableStateOf<QueueReplacePromptEvent?>(null) }
    LaunchedEffect(Unit) {
        playbackViewModel.queueReplacePrompts.collect { queueReplacePrompt = it }
    }

    // isPlaying/isBuffering/currentPosition/duration are collected inside BottomChromeOverlay
    // instead — currentPosition ticks every second while a song plays, and this composable
    // also hosts navigation/dialog state that has no reason to re-evaluate on that tick.
    // currentSong stays here: chromeBottomPadding below and the SnackbarHost Box's padding
    // both derive from it, and unlike position it only changes when the song itself changes.
    val currentSong by playbackViewModel.currentSong.collectAsState()

    val backupFolderNeedsAttention by BackupFolderLaunchCheck.grantLost.collectAsState()

    // 0f = collapsed mini player, 1f = fully expanded. Reported up by PlayerSheetSkeleton and
    // used to slide the bottom nav down in lockstep with the sheet's expansion.
    var playerProgress by remember { mutableFloatStateOf(0f) }
    // DEAD CODE: Home no longer draws ambient blobs, so it has nothing to pause.
    // Delete in a cleanup pass. Ask  before deleting.
    // val homeAmbientPaused = isHomeAmbientFullyOccluded(currentRoute, playerProgress)

    // Incremented to signal "collapse the expanded player" — e.g. when "Go to Album"/"Go to
    // Artist" navigates away from underneath it. An Int counter rather than a Boolean so a
    // second collapse request while already collapsed still re-triggers the LaunchedEffect
    // that observes it (a repeated `true` wouldn't).
    var collapsePlayerSignal by remember { mutableIntStateOf(0) }

    // Bottom chrome reservation, applied per destination (never on the NavHost itself, so
    // an outgoing screen can't reflow mid-transition).
    val chromeBottomPadding by remember(currentSong, showBottomBar) {
        derivedStateOf {
            when {
                !showBottomBar -> 0.dp
                currentSong != null -> BottomNavHeight + CollapsedPlayerHeight
                else -> BottomNavHeight
            }
        }
    }

    // Hosted here, not inside SongsScreen/SynthXNavHost, for the same reason SongActionsHost
    // is hosted here: a sheet nested inside the NavHost draws *under* BottomChromeOverlay
    // (composed after it in the Box below), so the bottom nav/player would paint over it.
    // SongsScreen only ever hands off "show it, with this order and this setter" via
    // onShowSortSheet — see SynthXNavHost/SongsScreen.
    var sortSheetTarget by remember { mutableStateOf<SortSheetTarget?>(null) }
    val sortSheetState = rememberRefinedBottomSheetState()
    val scope = rememberCoroutineScope()

    // Animate out, then drop from composition — clearing the target first would snap it shut.
    fun dismissSortSheet(after: () -> Unit = {}) {
        scope.launch { sortSheetState.hide() }.invokeOnCompletion {
            sortSheetTarget = null
            after()
        }
    }

    // The playlist-group picker, hoisted for exactly the reason the sort sheet above is: it is
    // opened from a PlaylistRow deep inside the NavHost, and a sheet composed there would draw
    // under the bottom nav/player. The screens hand over the groups to list, where the playlist
    // currently sits, and what to do with the pick — this shell holds no playlist state of its
    // own and never touches the repository, same contract as SortSheetTarget.
    var groupPickerTarget by remember { mutableStateOf<GroupPickerTarget?>(null) }
    val groupPickerSheetState = rememberRefinedBottomSheetState()

    fun dismissGroupPicker(after: () -> Unit = {}) {
        scope.launch { groupPickerSheetState.hide() }.invokeOnCompletion {
            groupPickerTarget = null
            after()
        }
    }

    // Hosted here so every SongRow inside the NavHost, and the PlayerSheetSkeleton below it,
    // share one sheet instance with navigation already wired.
    SongActionsHost(
        snackbarHostState = snackbarHostState,
        onNavigateToAlbum = { albumId, albumTitle ->
            collapsePlayerSignal++
            navController.navigate(Screen.AlbumDetail.createRoute(albumId, albumTitle))
        },
        onNavigateToArtist = { artistId, artistName ->
            collapsePlayerSignal++
            navController.navigate(Screen.ArtistDetail.createRoute(artistId, artistName))
        },
        onPlayNext = playbackViewModel::playNext,
        onAddToQueue = playbackViewModel::addToQueue,
        currentSongId = currentSong?.id,
    ) {
        Trace.beginSection(PerfTraceSections.MainShellContent)
        // Exposes test tags to UiAutomator as resource ids for the benchmark module.
        Box(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            // Excluded from TalkBack traversal while the sort sheet is open — see
            // hiddenFromAccessibilityWhileSheetShown's kdoc in RefinedBottomSheet.kt.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hiddenFromAccessibilityWhileSheetShown(
                        hidden = sortSheetTarget != null || groupPickerTarget != null,
                    ),
            ) {
                SynthXNavHost(
                    navController = navController,
                    playbackViewModel = playbackViewModel,
                    chromeBottomPadding = chromeBottomPadding,
                    searchReselectEvents = searchReselectEvents,
                    // DEAD CODE: see homeAmbientPaused above. Delete in a cleanup pass. Ask  before deleting.
                    // homeAmbientPaused = homeAmbientPaused,
                    onShowSortSheet = { order, onOrderSelected ->
                        sortSheetTarget = SortSheetTarget(order, onOrderSelected)
                    },
                    onShowGroupPicker = { groups, currentGroupId, onGroupSelected ->
                        groupPickerTarget = GroupPickerTarget(groups, currentGroupId, onGroupSelected)
                    },
                    startDestination = startDestination,
                    onRestoreTerminalShown = {
                        restoreStatusViewModel?.terminalWasShown(it)
                        restoreStatusViewModel?.dismissTerminal(it)
                    },
                )

                BottomChromeOverlay(
                    navController = navController,
                    currentRoute = currentRoute,
                    showBottomBar = showBottomBar,
                    currentSong = currentSong,
                    playerProgress = playerProgress,
                    onPlayerProgressChanged = { playerProgress = it },
                    collapsePlayerSignal = collapsePlayerSignal,
                    playbackViewModel = playbackViewModel,
                    onSearchReselected = { searchReselectEvents.tryEmit(Unit) },
                )

                if (backupFolderNeedsAttention) {
                    BackupFolderNotice(
                        onClick = {
                            BackupFolderLaunchCheck.dismiss()
                            navController.navigate(Screen.BackupsAndRestore.route)
                        },
                    )
                }
            }

            // Declared after BottomChromeOverlay (not nested inside SynthXNavHost) so it draws
            // above the bottom nav/player structurally — the same reason SongActionsHost's sheet
            // (an even higher-level sibling, above this whole Box) already draws correctly.
            sortSheetTarget?.let { target ->
                SortBottomSheet(
                    selectedOrder = target.selectedOrder,
                    sheetState = sortSheetState,
                    onOrderSelected = { order -> dismissSortSheet { target.onOrderSelected(order) } },
                    onDismiss = { dismissSortSheet() },
                )
            }

            // Same structural position, and the same reason for it, as the sort sheet above.
            groupPickerTarget?.let { target ->
                GroupPickerBottomSheet(
                    groups = target.groups,
                    currentGroupId = target.currentGroupId,
                    sheetState = groupPickerSheetState,
                    // Reassigning a group is non-destructive and instantaneous, so the pick closes
                    // the sheet and applies — no confirmation step, matching the picker's other
                    // single-tap consumers.
                    onGroupSelected = { groupId ->
                        dismissGroupPicker { target.onGroupSelected(groupId) }
                    },
                    onDismiss = { dismissGroupPicker() },
                )
            }

            // Declared last so it draws over the nav and player, and offset by the same
            // chrome reservation the destinations use so it never sits behind them. Both
            // are needed: chromeBottomPadding goes to 0 on routes without the bottom bar.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(bottom = chromeBottomPadding),
                contentAlignment = Alignment.BottomCenter,
            ) {
                SnackbarHost(hostState = snackbarHostState)
            }

            // Keep acts on the tapped song alone; Clear rebuilds from the list it came from.
            // Dismissing does neither — the current song and queue are left exactly as they are.
            queuePrompt?.let { prompt ->
                QueuePromptDialog(
                    onKeep = {
                        // Same confirmation strings SongActionsHost already shows for the
                        // ordinary Play Next action — reused rather than duplicated so the
                        // duplicate-queue message reads identically everywhere it appears.
                        // null: the engine refused for lack of a session connection and has
                        // already shown its own error snackbar, so there is nothing to confirm.
                        val message = when (playbackViewModel.keepQueueAndPlayNext(prompt.tappedSong)) {
                            true -> resources.getString(R.string.actions_playing_next)
                            false -> resources.getString(R.string.actions_already_playing_next)
                            null -> null
                        }
                        if (message != null) scope.launch { snackbarHostState.showSnackbar(message) }
                        queuePrompt = null
                    },
                    onClear = {
                        playbackViewModel.clearQueueAndPlay(prompt.contextSongs, prompt.startIndex)
                        queuePrompt = null
                    },
                    onDismiss = { queuePrompt = null },
                )
            }

            // Replace plays the request exactly as decided at tap time; Cancel and dismiss do nothing.
            queueReplacePrompt?.let { prompt ->
                QueueReplaceDialog(
                    onReplace = {
                        val request = prompt.request
                        playbackViewModel.playQueue(request.songs, request.startIndex, request.shuffle)
                        queueReplacePrompt = null
                    },
                    onCancel = { queueReplacePrompt = null },
                    onDismiss = { queueReplacePrompt = null },
                )
            }

            val routeOwnsRestoreOutcome = currentRoute == Screen.Onboarding.route ||
                currentRoute == Screen.Restore.route
            RestoreObservedStatusHost(restoreStatus, routeOwnsRestoreOutcome, restoreStatusViewModel)
        }
        Trace.endSection()
    }
    Trace.endSection()
}

internal fun isHomeAmbientFullyOccluded(currentRoute: String?, playerProgress: Float): Boolean =
    currentRoute == Screen.Home.route && playerProgress >= 0.999f

private data class SortSheetTarget(val selectedOrder: SortOrder, val onOrderSelected: (SortOrder) -> Unit)

/**
 * One open group picker: the groups to offer, the group the playlist is already in (null when it
 * is ungrouped, which is also what decides the sheet's title and whether any row is checked), and
 * the screen's own move-to-group call to run on the pick.
 */
private data class GroupPickerTarget(
    val groups: List<PlaylistGroup>,
    val currentGroupId: String?,
    val onGroupSelected: (groupId: String) -> Unit,
)
