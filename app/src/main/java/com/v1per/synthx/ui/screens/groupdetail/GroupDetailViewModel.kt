package com.v1per.synthx.ui.screens.groupdetail

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
import kotlinx.coroutines.launch

class GroupDetailViewModel(
    private val repository: SongRepository,
    private val groupId: String,
    private val appContext: Context,
) : ViewModel() {

    /** Resolved to app-voice strings.xml copy by the screen. */
    enum class ActionError { RenameFailed, DeleteFailed, RemoveFromGroupFailed, MoveToGroupFailed }

    private val _uiState = MutableStateFlow<UiState<List<Playlist>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Playlist>>> = _uiState.asStateFlow()

    // A failed rename/delete is a transient event, not a change to what's on screen — unlike
    // the initial load failure above, it must not replace the still-healthy playlist list with
    // UiState.Error (that used to blow away the whole group's view over one failed action).
    private val _actionErrors = MutableSharedFlow<ActionError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actionErrors: SharedFlow<ActionError> = _actionErrors.asSharedFlow()

    // Every group, including this one — the picker shows the current group checked and inert
    // rather than hiding it, so the list is not filtered here. Kept separate from [uiState]
    // (which drives this screen's own readiness cover) because a group-list load failure must
    // not blank out a perfectly good playlist list; an empty list simply means the "Move to
    // group" menu item doesn't render.
    private val _groups = MutableStateFlow<List<PlaylistGroup>>(emptyList())
    val groups: StateFlow<List<PlaylistGroup>> = _groups.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getPlaylistsForGroup(groupId)
                .catch { e ->
                    _uiState.value = UiState.Error(LoadError.GroupPlaylists.message(appContext))
                }
                .collect { playlists ->
                    _uiState.value =
                        if (playlists.isEmpty()) UiState.Empty else UiState.Success(data = playlists)
                }
        }
        viewModelScope.launch {
            repository.getAllGroups()
                .catch { _groups.value = emptyList() }
                .collect { _groups.value = it }
        }
    }

    // Reassignment only: the picker never offers the null (ungroup) case, so this cannot be
    // reached with anything but a real group id. Non-destructive and unconfirmed by design —
    // movePlaylistToGroup overwrites group_id outright, and Room's invalidation drops the row
    // from this group's list once the write lands, exactly as removeFromGroup below does.
    // targetGroupId, not groupId: the constructor property of that name is the group being
    // *viewed*, and shadowing it here is exactly the confusion this call is fixing.
    fun moveToGroup(id: String, targetGroupId: String) {
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.MoveToGroupFailed) }) {
            repository.movePlaylistToGroup(id, targetGroupId)
        }
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.RenameFailed) }) {
            repository.renamePlaylist(id, name.trim())
        }
    }

    fun deletePlaylist(id: String) {
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.DeleteFailed) }) {
            repository.deletePlaylist(id)
        }
    }

    // Ungroups only — the playlist and its songs are untouched, unlike deletePlaylist above.
    // Room's invalidation drops the row from this group's list on its own once the group_id
    // write lands, the same way deletePlaylist's removal shows up without a local list edit.
    fun removeFromGroup(id: String) {
        launchCatching(onError = { _actionErrors.tryEmit(ActionError.RemoveFromGroupFailed) }) {
            repository.movePlaylistToGroup(id, null)
        }
    }

    companion object {
        fun factory(context: Context, groupId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return GroupDetailViewModel(repository, groupId, context.applicationContext) as T
                }
            }
    }
}
