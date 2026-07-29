package com.v1per.synthx.ui.screens.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
}

sealed class NavigationEvent {
    object GoToSongs : NavigationEvent()
    object GoToArtistList : NavigationEvent()
}

class LibraryViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()

    private val _recentSongs = MutableStateFlow<List<Song>>(emptyList())
    val recentSongs: StateFlow<List<Song>> = _recentSongs.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getSongs()
                .map { songs ->
                    songs.sortedByDescending { it.dateAdded }.take(20)
                }
                .collect { _recentSongs.value = it }
        }
    }

    fun onPlaylistsClick() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Coming soon"))
        }
    }

    fun onArtistsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToArtistList)
        }
    }

    fun onAlbumsClick() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Coming soon"))
        }
    }

    fun onSongsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToSongs)
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return LibraryViewModel(repository) as T
                }
            }
    }
}
