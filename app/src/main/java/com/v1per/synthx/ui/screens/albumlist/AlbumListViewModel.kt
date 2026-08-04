package com.v1per.synthx.ui.screens.albumlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AlbumListViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<Album>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Album>>> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAlbums()
                .catch { e ->
                    _uiState.value = UiState.Error(e.message ?: "Failed to load albums")
                }
                .collect { albums ->
                    _uiState.value =
                        if (albums.isEmpty()) UiState.Empty else UiState.Success(data = albums)
                }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return AlbumListViewModel(repository) as T
                }
            }
    }
}
