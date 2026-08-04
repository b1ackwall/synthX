package com.v1per.synthx.ui.screens.artistdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ArtistDetailViewModel(
    private val repository: SongRepository,
    private val artistId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Song>>> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val songs = repository.getSongsForArtist(artistId)
                _uiState.value =
                    if (songs.isEmpty()) UiState.Empty else UiState.Success(data = songs)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load songs")
            }
        }
    }

    companion object {
        fun factory(context: Context, artistId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return ArtistDetailViewModel(repository, artistId) as T
                }
            }
    }
}
