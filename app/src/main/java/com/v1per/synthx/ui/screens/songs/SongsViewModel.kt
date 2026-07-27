package com.v1per.synthx.ui.screens.songs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.playback.PlaybackViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

sealed class UiState {
    object Loading : UiState()
    object Empty : UiState()
    data class Success(val songs: List<Song>) : UiState()
    data class Error(val message: String) : UiState()
}

class SongsViewModel(
    private val repository: SongRepository,
    private val playbackViewModel: PlaybackViewModel,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val currentlyPlayingSongId: StateFlow<String?> = playbackViewModel.currentSongId

    init {
        observeSongs()
    }

    private fun observeSongs() {
        viewModelScope.launch {
            repository.getSongs()
                .catch { e ->
                    _uiState.value = UiState.Error(e.message ?: "Failed to load songs")
                }
                .collect { songs ->
                    _uiState.value =
                        if (songs.isEmpty()) UiState.Empty else UiState.Success(songs)
                }
        }
    }

    fun onSongTap(song: Song) {
        playbackViewModel.playSong(song)
    }

    class Factory(
        private val context: Context,
        private val playbackViewModel: PlaybackViewModel,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SongsViewModel(SongRepositoryImpl.create(context), playbackViewModel) as T
    }
}
