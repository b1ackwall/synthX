package com.v1per.synthx.ui.screens.playlistdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    private val repository: SongRepository,
    private val playlistId: String,
) : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        object Empty : UiState()
        data class Success(val songs: List<Song>) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getSongsForPlaylist(playlistId)
                .collect { songs ->
                    _uiState.value =
                        if (songs.isEmpty()) UiState.Empty else UiState.Success(songs)
                }
        }
    }

    companion object {
        fun factory(context: Context, playlistId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return PlaylistDetailViewModel(repository, playlistId) as T
                }
            }
    }
}
