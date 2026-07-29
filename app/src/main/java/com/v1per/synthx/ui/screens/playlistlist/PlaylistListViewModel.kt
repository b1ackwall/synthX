package com.v1per.synthx.ui.screens.playlistlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlaylistListViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    data class PlaylistListUiState(
        val groups: List<PlaylistGroup> = emptyList(),
        val ungroupedPlaylists: List<Playlist> = emptyList(),
        val isLoading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(PlaylistListUiState())
    val uiState: StateFlow<PlaylistListUiState> = _uiState.asStateFlow()

    // Inline group creation card state
    private val _showCreateGroup = MutableStateFlow(false)
    val showCreateGroup: StateFlow<Boolean> = _showCreateGroup.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.getAllGroups(),
                repository.getUngroupedPlaylists(),
            ) { groups, playlists ->
                PlaylistListUiState(
                    groups = groups,
                    ungroupedPlaylists = playlists,
                    isLoading = false,
                )
            }.collect { _uiState.value = it }
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
        viewModelScope.launch {
            repository.createGroup(name.trim())
            _showCreateGroup.value = false
        }
    }

    fun renameGroup(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameGroup(id, name.trim()) }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch { repository.deleteGroup(id) }
    }

    fun movePlaylistToGroup(playlistId: String, groupId: String?) {
        viewModelScope.launch { repository.movePlaylistToGroup(playlistId, groupId) }
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renamePlaylist(id, name.trim()) }
    }

    fun deletePlaylist(id: String) {
        // Placeholder — no-op for now, secure delete deferred
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return PlaylistListViewModel(repository) as T
                }
            }
    }
}
