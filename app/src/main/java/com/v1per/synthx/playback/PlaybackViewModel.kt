package com.v1per.synthx.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class PlaybackViewModel(private val controller: PlaybackController) : ViewModel() {

    val isPlaying: StateFlow<Boolean> = controller.isPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentSongId: StateFlow<String?> = controller.currentSongId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun playSong(song: Song) = controller.playSong(song)
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()

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
