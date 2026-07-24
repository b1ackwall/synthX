package com.v1per.synthx.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
}

sealed class NavigationEvent {
    object GoToSongs : NavigationEvent()
}

class LibraryViewModel : ViewModel() {

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()

    fun onPlaylistsClick() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Coming soon"))
        }
    }

    fun onArtistsClick() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Coming soon"))
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
}
