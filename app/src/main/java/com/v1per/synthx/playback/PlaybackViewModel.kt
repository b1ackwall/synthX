package com.v1per.synthx.playback

import android.content.Context
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

    val currentPosition: StateFlow<Long> = controller.currentPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val duration: StateFlow<Long> = controller.duration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    init {
        // Mirror currentSongId going null (track end, stop) into currentSong
        viewModelScope.launch {
            currentSongId.collect { id ->
                if (id == null) _currentSong.value = null
            }
        }
    }

    // Stays a plain fun so every UI call site keeps working — the engine's suspend
    // artwork work is launched into viewModelScope (Main), which is also the
    // dispatcher MediaController requires when the engine resumes.
    fun playSong(song: Song) {
        _currentSong.value = song
        viewModelScope.launch { controller.playSong(song) }
    }

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun stop() = controller.stop()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun togglePlayPause() {
        if (isPlaying.value) pause() else resume()
    }

    override fun onCleared() {
        super.onCleared()
        controller.release()
    }

    /**
     * Builds the engine inside [create] rather than holding a prebuilt one. An
     * ExoPlayerEngine opens a MediaController binding to SynthXPlaybackService in its
     * constructor, so constructing one eagerly — as a factory constructor argument —
     * orphans that binding whenever ViewModelProvider finds an existing ViewModel and
     * never calls [create]. Only the Context is held here; it is cheap to rebuild.
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(PlaybackViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val engine = ExoPlayerEngine(context.applicationContext)
            val controller = PlaybackController(engine)
            return PlaybackViewModel(controller) as T
        }
    }
}
