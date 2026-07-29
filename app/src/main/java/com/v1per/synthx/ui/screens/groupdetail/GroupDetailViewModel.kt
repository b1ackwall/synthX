package com.v1per.synthx.ui.screens.groupdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GroupDetailViewModel(
    private val repository: SongRepository,
    private val groupId: String,
) : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        object Empty : UiState()
        data class Success(val playlists: List<Playlist>) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getPlaylistsForGroup(groupId)
                .collect { playlists ->
                    _uiState.value =
                        if (playlists.isEmpty()) UiState.Empty else UiState.Success(playlists)
                }
        }
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renamePlaylist(id, name.trim()) }
    }

    fun deletePlaylist(id: String) {
        // Placeholder — no-op for now, secure delete deferred
    }

    companion object {
        fun factory(context: Context, groupId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return GroupDetailViewModel(repository, groupId) as T
                }
            }
    }
}
