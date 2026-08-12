package com.v1per.synthx.ui.screens.artistlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.LoadError
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class ArtistListViewModel(
    private val repository: SongRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<Artist>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Artist>>> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getArtists()
                .catch { e ->
                    _uiState.value = UiState.Error(LoadError.Artists.message(appContext))
                }
                .collect { artists ->
                    _uiState.value =
                        if (artists.isEmpty()) UiState.Empty else UiState.Success(data = artists)
                }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return ArtistListViewModel(repository, context.applicationContext) as T
                }
            }
    }
}
