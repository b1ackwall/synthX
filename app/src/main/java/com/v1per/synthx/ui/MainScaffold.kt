package com.v1per.synthx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackError
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.rememberNotificationPermissionGate
import com.v1per.synthx.playback.QueuePromptEvent
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.GroupPickerBottomSheet
import com.v1per.synthx.ui.components.QueuePromptDialog
import com.v1per.synthx.ui.components.hiddenFromAccessibilityWhileSheetShown
import com.v1per.synthx.ui.components.SongActionsHost
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.components.rememberSharedSongBlobColors
import com.v1per.synthx.ui.screens.songs.SortBottomSheet
import com.v1per.synthx.ui.screens.songs.SortOrder
import com.v1per.synthx.ui.screens.restore.RestoreAppStatus
import com.v1per.synthx.ui.screens.restore.RestoreStatusViewModel
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

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
 * Shown for the whole of [RestoreCoordinator.Mode.RESTORE_PREPARING]: database admission is
 * closed, so nothing here may read Room, and no route out may exist.
 *
 * This is a sibling of the nav graph, not an overlay on top of it. Being the composed branch is
 * what makes it a block rather than a cover — [NormalShell] is not in the composition at all, so
 * no destination, ViewModel or repository Flow behind it can be created or resubscribed.
 */
@Composable
private fun RestoreMaintenanceSurface() {
    // The nav graph is gone, so there is nothing left to pop — this stops back from finishing
    // the Activity out from under an in-flight restore instead.
    BackHandler {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .testTag(ShellTestTags.RestoreMaintenanceSurface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // TextSecondary, not Accent: the neon is reserved for the five documented contexts.
            CircularProgressIndicator(color = TextSecondary, strokeWidth = 2.dp)
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.restore_maintenance_title),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.restore_maintenance_message),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
            )
        }
    }
}

/** Unchanged from the guard this shell has always had; only its call site moved. */
@Composable
private fun RestoreTerminalSurface(
    restoreTerminal: RestoreCoordinator.Terminal,
    diagnosticOperationId: String?,
) {
    val message = when (restoreTerminal) {
        RestoreCoordinator.Terminal.RESTART_REQUIRED -> "Restore complete. Close and reopen synthX to continue."
        RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED -> "Restore was rolled back safely. Close and reopen synthX to continue."
        RestoreCoordinator.Terminal.EMERGENCY ->
            "Automatic database recovery could not validate a usable database.\n\n" +
                "Normal app use is disabled to protect your data.\n\n" +
                "Diagnostic operation ID:\n${diagnosticOperationId ?: "unavailable"}"
        RestoreCoordinator.Terminal.NORMAL -> ""
    }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                if (restoreTerminal == RestoreCoordinator.Terminal.EMERGENCY) {
                    "Database recovery required"
                } else {
                    "Restart required"
                }
            )
        },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = { exitProcess(0) },
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) { Text("Close") }
        },
    )
}

/**
 * Minimal, non-blocking notice for P7.2 — [BackupFolderLaunchCheck] runs once per process and
 * this is its only surface. Reuses the plain Text-row shape already used for backupFolderError
 * on the Backups & Restore screen itself (see BackupSection.kt) rather than introducing a new
 * banner system; tapping it clears the flag and hands off to that screen, where Fix 2's own
 * re-check and the actual repair flow (Fix 1) live.
 */
@Composable
private fun BackupFolderNotice(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.backup_folder_needs_attention),
            style = MaterialTheme.typography.bodySmall,
            color = Error,
        )
    }
}

/**
 * P9-T5 / F19 second chance: the first time playback actually runs, offer the notification
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

@Composable
private fun NormalShell(
    navController: NavHostController,
    startDestination: String,
    restoreStatusViewModel: RestoreStatusViewModel?,
) {
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
    val playbackViewModel = viewModel<PlaybackViewModel>(
        factory = PlaybackViewModel.Factory(context.applicationContext)
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

    // context.getString rather than stringResource: the collect lambda is not a composable
    // scope, and the message needs the song title formatted in at emit time.
    LaunchedEffect(Unit) {
        playbackViewModel.errors.collect { error ->
            val message = when (error) {
                is PlaybackError.PlaybackFailed ->
                    context.getString(R.string.playback_error_failed, error.songTitle)
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

    // isPlaying/isBuffering/currentPosition/duration are collected inside BottomChromeOverlay
    // instead — currentPosition ticks every second while a song plays, and this composable
    // also hosts navigation/dialog state that has no reason to re-evaluate on that tick.
    // currentSong stays here: chromeBottomPadding below and the SnackbarHost Box's padding
    // both derive from it, and unlike position it only changes when the song itself changes.
    val currentSong by playbackViewModel.currentSong.collectAsState()
    // One arrangement for both Home and player. The helper keys arrangement only by song id and
    // reapplies it when the same Song copy receives backfilled palette values.
    val ambientColors = rememberSharedSongBlobColors(currentSong)

    val backupFolderNeedsAttention by BackupFolderLaunchCheck.grantLost.collectAsState()

    // 0f = collapsed mini player, 1f = fully expanded. Reported up by PlayerSheet and used
    // to slide the bottom nav down in lockstep with the sheet's expansion.
    var playerProgress by remember { mutableStateOf(0f) }
    val homeAmbientPaused = isHomeAmbientFullyOccluded(currentRoute, playerProgress)

    // Incremented to signal "collapse the expanded player" — e.g. when "Go to Album"/"Go to
    // Artist" navigates away from underneath it. An Int counter rather than a Boolean so a
    // second collapse request while already collapsed still re-triggers the LaunchedEffect
    // that observes it (a repeated `true` wouldn't).
    var collapsePlayerSignal by remember { mutableStateOf(0) }

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

    // Hosted here so every SongRow inside the NavHost, and the PlayerSheet below it,
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
    Box(modifier = Modifier.fillMaxSize()) {
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
                homeAmbientPaused = homeAmbientPaused,
                ambientColors = ambientColors,
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
                ambientColors = ambientColors,
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
                    val added = playbackViewModel.keepQueueAndPlayNext(prompt.tappedSong)
                    val message = if (added) {
                        context.getString(R.string.actions_playing_next)
                    } else {
                        context.getString(R.string.actions_already_playing_next)
                    }
                    scope.launch { snackbarHostState.showSnackbar(message) }
                    queuePrompt = null
                },
                onClear = {
                    playbackViewModel.clearQueueAndPlay(prompt.contextSongs, prompt.startIndex)
                    queuePrompt = null
                },
                onDismiss = { queuePrompt = null },
            )
        }

        val routeOwnsRestoreOutcome = currentRoute == Screen.Onboarding.route ||
            currentRoute == Screen.Restore.route
        RestoreObservedStatusHost(restoreStatus, routeOwnsRestoreOutcome, restoreStatusViewModel)
    }
    }
}

@Composable
internal fun RestoreObservedStatusHost(
    status: RestoreAppStatus,
    routeOwnsRestoreOutcome: Boolean,
    viewModel: RestoreStatusViewModel?,
) {
    if (status is RestoreAppStatus.WaitingForStorage) RestorePendingBanner()

    val terminal = status as? RestoreAppStatus.Terminal
    if (terminal != null && !routeOwnsRestoreOutcome) {
        LaunchedEffect(terminal.requestId) { viewModel?.terminalWasShown(terminal.requestId) }
        RestoreObservedOutcomeDialog(
            terminal = terminal,
            onDismiss = { viewModel?.dismissTerminal(terminal.requestId) },
        )
    }
}

@Composable
private fun RestorePendingBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(SurfaceVariant)
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm)
            .testTag(ShellTestTags.RestorePending),
    ) {
        Text(
            text = stringResource(R.string.restore_pending_storage),
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
        )
    }
}

@Composable
private fun RestoreObservedOutcomeDialog(
    terminal: RestoreAppStatus.Terminal,
    onDismiss: () -> Unit,
) {
    val succeeded = terminal is RestoreAppStatus.Succeeded
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(stringResource(if (succeeded) R.string.restore_done_title else R.string.restore_failed_title))
        },
        text = {
            Text(
                if (terminal is RestoreAppStatus.Failed) terminal.message
                else stringResource(R.string.restore_done_message),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

internal fun isHomeAmbientFullyOccluded(currentRoute: String?, playerProgress: Float): Boolean =
    currentRoute == Screen.Home.route && playerProgress >= 0.999f

private data class SortSheetTarget(
    val selectedOrder: SortOrder,
    val onOrderSelected: (SortOrder) -> Unit,
)

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
