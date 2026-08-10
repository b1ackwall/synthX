package com.v1per.synthx.ui.screens.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed class NavigationEvent {
    object GoToSongs : NavigationEvent()
    object GoToArtistList : NavigationEvent()
    object GoToAlbumList : NavigationEvent()
    object GoToPlaylistList : NavigationEvent()
}

class LibraryViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    // Buffered so a click emitted while the screen is between compositions
    // is delivered on resubscribe instead of being dropped.
    private val _navigationEvents = MutableSharedFlow<NavigationEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()

    private val _recentSongs = MutableStateFlow<List<Song>>(emptyList())
    val recentSongs: StateFlow<List<Song>> = _recentSongs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // True only until the first emission (success or failure) lands, so the Recently Added
    // section can distinguish "still loading" from "loaded and genuinely empty" — both used
    // to render as nothing.
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getSongs()
                .map { songs ->
                    // Filtered before the take, so a run of missing songs can't crowd out
                    // playable ones. RecentSongTile has no disabled state, unlike SongRow.
                    songs.filter { !it.isMissing }
                        .sortedByDescending { it.dateAdded }
                        .take(20)
                }
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    _error.value = "Failed to load recently added: ${e.message}"
                    _isLoading.value = false
                }
                .collect {
                    _recentSongs.value = it
                    _error.value = null
                    _isLoading.value = false
                }
        }
    }

    fun onPlaylistsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToPlaylistList)
        }
    }

    fun onArtistsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToArtistList)
        }
    }

    fun onAlbumsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToAlbumList)
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
