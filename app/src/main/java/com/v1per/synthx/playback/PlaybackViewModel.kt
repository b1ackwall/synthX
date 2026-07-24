package com.v1per.synthx.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaybackViewModel(private val controller: PlaybackController) : ViewModel() {

    val isPlaying: StateFlow<Boolean> = controller.isPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentSongId: StateFlow<String?> = controller.currentSongId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    init {
        // Mirror currentSongId going null (track end, stop) into currentSong
        viewModelScope.launch {
            currentSongId.collect { id ->
                if (id == null) _currentSong.value = null
            }
        }
    }

    fun playSong(song: Song) {
        _currentSong.value = song
        controller.playSong(song)
    }

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun stop() = controller.stop()

    fun togglePlayPause() {
        if (isPlaying.value) pause() else resume()
    }

    override fun onCleared() {
        super.onCleared()
        controller.release()
    }

    class Factory(private val controller: PlaybackController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaybackViewModel(controller) as T
    }
}
