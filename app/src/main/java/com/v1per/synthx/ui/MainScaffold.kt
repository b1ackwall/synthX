package com.v1per.synthx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackError
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.playback.QueuePromptEvent
import com.v1per.synthx.ui.components.BottomNavHeight
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.QueuePromptDialog
import com.v1per.synthx.ui.components.SongActionsHost
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.screens.songs.SortBottomSheet
import com.v1per.synthx.ui.screens.songs.SortOrder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

@Composable
fun MainScaffold(navController: NavHostController = rememberNavController()) {
    val restoreTerminal by RestoreCoordinator.terminal.collectAsState()
    val diagnosticOperationId by RestoreCoordinator.diagnosticOperationId.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Home/Library/Search plus every route in Library's own detail hierarchy — see
    // Screen.libraryDetailRoutes, also shared by SynthXBottomNav.
    val bottomBarRoutes = setOf(
        Screen.Home.route,
        Screen.Library.route,
        Screen.Search.route,
    ) + Screen.libraryDetailRoutes
    val showBottomBar = currentRoute in bottomBarRoutes

    val context = LocalContext.current
    if (restoreTerminal != RestoreCoordinator.Terminal.NORMAL) {
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
            confirmButton = { TextButton(onClick = { exitProcess(0) }) { Text("Close") } },
        )
        return
    }
    val playbackViewModel = viewModel<PlaybackViewModel>(
        factory = PlaybackViewModel.Factory(context.applicationContext)
    )
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

    // 0f = collapsed mini player, 1f = fully expanded. Reported up by PlayerSheet and used
    // to slide the bottom nav down in lockstep with the sheet's expansion.
    var playerProgress by remember { mutableStateOf(0f) }

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
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        SynthXNavHost(
            navController = navController,
            playbackViewModel = playbackViewModel,
            chromeBottomPadding = chromeBottomPadding,
            searchReselectEvents = searchReselectEvents,
            onShowSortSheet = { order, onOrderSelected ->
                sortSheetTarget = SortSheetTarget(order, onOrderSelected)
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
                    playbackViewModel.keepQueueAndPlayNext(prompt.tappedSong)
                    queuePrompt = null
                },
                onClear = {
                    playbackViewModel.clearQueueAndPlay(prompt.contextSongs, prompt.startIndex)
                    queuePrompt = null
                },
                onDismiss = { queuePrompt = null },
            )
        }
    }
    }
}

private data class SortSheetTarget(
    val selectedOrder: SortOrder,
    val onOrderSelected: (SortOrder) -> Unit,
)
