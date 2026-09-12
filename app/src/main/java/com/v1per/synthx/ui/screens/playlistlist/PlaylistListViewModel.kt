package com.v1per.synthx.ui.screens.playlistlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.LoadError
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.common.launchCatching
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The screen's two lists, loaded together — see [PlaylistListViewModel.uiState]. */
data class PlaylistListData(
    val groups: List<PlaylistGroup>,
    val ungroupedPlaylists: List<Playlist>,
)

class PlaylistListViewModel(
    private val repository: SongRepository,
    private val appContext: Context,
    private val stringResource: (Int) -> String = { id -> appContext.getString(id) },
) : ViewModel() {

    /** Resolved to app-voice strings.xml copy by the screen — see
     *  GroupDetailViewModel.ActionError for the same pattern. */
    enum class ActionError {
        RenamePlaylistFailed, DeletePlaylistFailed, MovePlaylistFailed,
        RenameGroupFailed, DeleteGroupFailed, CreateGroupFailed,
    }

    private val _uiState = MutableStateFlow<UiState<PlaylistListData>>(UiState.Loading)
    val uiState: StateFlow<UiState<PlaylistListData>> = _uiState.asStateFlow()

    // Inline group creation card state
    private val _showCreateGroup = MutableStateFlow(false)
    val showCreateGroup: StateFlow<Boolean> = _showCreateGroup.asStateFlow()

    // A failed action is a transient event, not a change to what's on screen — see
    // GroupDetailViewModel._actionErrors for the same reasoning.
    private val _actionErrors = MutableSharedFlow<ActionError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actionErrors: SharedFlow<ActionError> = _actionErrors.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.getAllGroups(),
                repository.getUngroupedPlaylists(),
            ) { groups, playlists ->
                PlaylistListData(groups = groups, ungroupedPlaylists = playlists)
            }
                .catch { e ->
                    _uiState.value = UiState.Error(LoadError.PlaylistList.message(stringResource))
                }
                .collect { data ->
                    _uiState.value = if (data.groups.isEmpty() && data.ungroupedPlaylists.isEmpty()) {
                        UiState.Empty
                    } else {
                        UiState.Success(data)
                    }
                }
        }
    }

    fun onCreateGroupClick() {
        _showCreateGroup.value = true
    }

    fun onDismissCreateGroup() {
        _showCreateGroup.value = false
    }

    fun createGroup(name: String) {
        if (name.isBlank()) return
        // Dismissed before the write completes, not after: the card and its confirm button
        // otherwise stay open and tappable for the full round trip, which read as unresponsive
        // and let a slow write or a double-tap re-fire this. The card reappearing on a genuine
        // failure would be surprising, but createGroup has no realistic failure mode a user
        // could act on differently than "try again" — same tradeoff Apple Music's own inline
        // creation flows make.
        _showCreateGroup.value = false
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.CreateGroupFailed) }) {
            repository.createGroup(name.trim())
        }
    }

    fun renameGroup(id: String, name: String) {
        if (name.isBlank()) return
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.RenameGroupFailed) }) {
            repository.renameGroup(id, name.trim())
        }
    }

    fun deleteGroup(id: String) {
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.DeleteGroupFailed) }) {
            repository.deleteGroup(id)
        }
    }

    fun movePlaylistToGroup(playlistId: String, groupId: String?) {
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.MovePlaylistFailed) }) {
            repository.movePlaylistToGroup(playlistId, groupId)
        }
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.RenamePlaylistFailed) }) {
            repository.renamePlaylist(id, name.trim())
        }
    }

    fun deletePlaylist(id: String) {
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.DeletePlaylistFailed) }) {
            repository.deletePlaylist(id)
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    val repository = SongRepositoryImpl.create(appContext)
                    @Suppress("UNCHECKED_CAST")
                    return PlaylistListViewModel(repository, appContext) as T
                }
            }
    }
}
