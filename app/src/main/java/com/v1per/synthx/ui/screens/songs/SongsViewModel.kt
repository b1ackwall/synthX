package com.v1per.synthx.ui.screens.songs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.playback.PlaybackViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

sealed class UiState {
    object Loading : UiState()
    object Empty : UiState()
    data class Success(val songs: List<Song>) : UiState()
    data class Error(val message: String) : UiState()
}

enum class SortOrder {
    RECENTLY_ADDED,
    TITLE,
    ARTIST,
    ALBUM,
}

class SongsViewModel(
    private val repository: SongRepository,
    private val playbackViewModel: PlaybackViewModel,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.RECENTLY_ADDED)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    val currentlyPlayingSongId: StateFlow<String?> = playbackViewModel.currentSongId

    init {
        observeSongs()
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    private fun observeSongs() {
        viewModelScope.launch {
            combine(_sortOrder, repository.getSongs()) { order, songs ->
                songs.sorted(order)
            }
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    _uiState.value = UiState.Error(e.message ?: "Failed to load songs")
                }
                .collect { songs ->
                    _uiState.value =
                        if (songs.isEmpty()) UiState.Empty else UiState.Success(songs)
                }
        }
    }

    private fun List<Song>.sorted(order: SortOrder): List<Song> = when (order) {
        SortOrder.RECENTLY_ADDED -> sortedByDescending { it.dateAdded }
        SortOrder.TITLE -> sortedBy { it.title.lowercase() }
        SortOrder.ARTIST -> sortedBy { it.artist.lowercase() }
        SortOrder.ALBUM -> sortedBy { it.album.lowercase() }
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
