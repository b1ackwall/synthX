package com.v1per.synthx.ui.screens.groupdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class GroupDetailViewModel(
    private val repository: SongRepository,
    private val groupId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<Playlist>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Playlist>>> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getPlaylistsForGroup(groupId)
                .catch { e ->
                    _uiState.value = UiState.Error("Failed to load playlists: ${e.message}")
                }
                .collect { playlists ->
                    _uiState.value =
                        if (playlists.isEmpty()) UiState.Empty else UiState.Success(data = playlists)
                }
        }
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                repository.renamePlaylist(id, name.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to rename playlist: ${e.message}")
            }
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            try {
                repository.deletePlaylist(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to delete playlist: ${e.message}")
            }
        }
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
