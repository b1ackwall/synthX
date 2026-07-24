package com.v1per.synthx.ui.screens.songs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UiState {
    object Loading : UiState()
    object Empty : UiState()
    data class Success(val songs: List<Song>) : UiState()
    data class Error(val message: String) : UiState()
}

class SongsViewModel(private val repository: SongRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _currentlyPlayingSongId = MutableStateFlow<String?>(null)
    val currentlyPlayingSongId: StateFlow<String?> = _currentlyPlayingSongId.asStateFlow()

    init {
        loadSongs()
    }

    private fun loadSongs() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val songs = repository.getSongs()
                _uiState.value = if (songs.isEmpty()) UiState.Empty else UiState.Success(songs)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load songs")
            }
        }
    }

    class Factory(
        private val context: Context,
        private val preferencesManager: PreferencesManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SongsViewModel(SongRepositoryImpl(context, preferencesManager)) as T
    }
}
