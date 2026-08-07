package com.v1per.synthx.ui.components

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Opens the song actions sheet from anywhere inside [SongActionsHost]. */
fun interface SongActionsController {
    fun show(song: Song, currentPlaylistId: String?)
}

/** Outcomes worth telling the user about, surfaced as a Snackbar by [SongActionsHost]. */
sealed class SongActionEvent {
    data class AlreadyInPlaylist(val playlistName: String) : SongActionEvent()
}

/**
 * SongRow appears on five screens, and the actions sheet needs navigation, a repository
 * and its own sheet state. Threading all of that through five call sites (and their
 * ViewModels) would mean the same five callbacks drilled through every screen that
 * happens to render a list of songs. Instead the sheet is hosted once, above the
 * NavHost, and rows reach it through composition.
 *
 * Defaults to a no-op so previews and tests can render SongRow without a host.
 */
val LocalSongActions: ProvidableCompositionLocal<SongActionsController> =
    compositionLocalOf { SongActionsController { _, _ -> } }

/**
 * Owns the single SongActionsBottomSheet instance and provides [LocalSongActions] to
 * [content]. Place inside whatever composable owns the NavController, so the navigation
 * callbacks can be supplied here rather than plumbed down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsHost(
    snackbarHostState: SnackbarHostState,
    onNavigateToAlbum: (albumId: String, albumTitle: String) -> Unit,
    onNavigateToArtist: (artistId: String, artistName: String) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SongActionsViewModel = viewModel(
        factory = SongActionsViewModel.Factory(context),
    )
    val playlists by viewModel.playlists.collectAsState()

    // context.getString rather than stringResource: the collect lambda is not a composable
    // scope, and the playlist name is only known at emit time.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SongActionEvent.AlreadyInPlaylist ->
                    context.getString(R.string.actions_already_in_playlist, event.playlistName)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    var target by remember { mutableStateOf<SongActionsTarget?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Animate out, then drop from composition — clearing the flag first would snap it shut.
    fun dismiss(after: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            target = null
            after()
        }
    }

    val controller = remember {
        SongActionsController { song, playlistId ->
            target = SongActionsTarget(song, playlistId)
        }
    }

    CompositionLocalProvider(LocalSongActions provides controller) {
        content()
    }

    target?.let { current ->
        SongActionsBottomSheet(
            song = current.song,
            sheetState = sheetState,
            playlists = playlists,
            currentPlaylistId = current.playlistId,
            onDeleteSong = {
                val id = current.song.id
                dismiss { viewModel.deleteSong(id) }
            },
            // The detail routes take a display name for their top bar; the song already
            // carries both, so callers never have to look them up.
            onGoToAlbum = { albumId ->
                val title = current.song.album
                dismiss { onNavigateToAlbum(albumId, title) }
            },
            onGoToArtist = { artistId ->
                val name = current.song.artists.firstOrNull { it.id == artistId }?.name.orEmpty()
                dismiss { onNavigateToArtist(artistId, name) }
            },
            onAddToPlaylist = { playlistId ->
                val songId = current.song.id
                dismiss { viewModel.addToPlaylist(playlistId, songId) }
            },
            onRemoveFromPlaylist = {
                val playlistId = current.playlistId
                val songId = current.song.id
                dismiss { if (playlistId != null) viewModel.removeFromPlaylist(playlistId, songId) }
            },
            onDismiss = { dismiss() },
        )
    }
}

private data class SongActionsTarget(val song: Song, val playlistId: String?)

class SongActionsViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.getAllPlaylists()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // One-off UI events, matching LibraryViewModel's NavigationEvent and the playback
    // error flow: buffered by one, newest wins, never replayed to a new collector.
    private val _events = MutableSharedFlow<SongActionEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SongActionEvent> = _events.asSharedFlow()

    fun deleteSong(songId: String) {
        viewModelScope.launch { repository.deleteSong(songId) }
    }

    fun addToPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            val added = repository.addSongToPlaylist(playlistId, songId)
            if (!added) {
                val name = playlists.value.firstOrNull { it.id == playlistId }?.name.orEmpty()
                _events.tryEmit(SongActionEvent.AlreadyInPlaylist(name))
            }
        }
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch { repository.removeSongFromPlaylist(playlistId, songId) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SongActionsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return SongActionsViewModel(SongRepositoryImpl.create(context)) as T
        }
    }
}
