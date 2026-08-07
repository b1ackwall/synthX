package com.v1per.synthx.playback

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.flow.Flow
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

    // Eagerly, unlike the state below: currentSong is resolved against queue.value in
    // init, which only holds a real list while something is subscribed. The upstream is a
    // hot StateFlow in the engine, so there is nothing to keep warm at a cost.
    val queue: StateFlow<List<Song>> = controller.queue
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentIndex: StateFlow<Int> = controller.currentIndex
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    val shuffleEnabled: StateFlow<Boolean> = controller.shuffleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val repeatMode: StateFlow<RepeatMode> = controller.repeatMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepeatMode.Off)

    val currentPosition: StateFlow<Long> = controller.currentPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val duration: StateFlow<Long> = controller.duration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // Passed through as a plain Flow, unlike the state above: these are one-off events,
    // and stateIn/shareIn would replay the last failure to every new collector — showing
    // a stale snackbar on every navigation back to the scaffold.
    val errors: Flow<PlaybackError> = controller.errors

    init {
        // Mirror currentSongId into currentSong: null (queue end, stop) clears it, and an
        // auto-advance to the next queue item resolves the new id against the queue. The
        // fallback keeps the previous song rather than blanking the player if the id
        // arrives before the queue does.
        viewModelScope.launch {
            currentSongId.collect { id ->
                _currentSong.value = when (id) {
                    null -> null
                    else -> queue.value.firstOrNull { it.id == id } ?: _currentSong.value
                }
            }
        }
    }

    // Stays a plain fun so every UI call site keeps working — the engine's suspend
    // artwork work is launched into viewModelScope (Main), which is also the
    // dispatcher MediaController requires when the engine resumes.
    fun playQueue(songs: List<Song>, startIndex: Int) {
        // Set optimistically so the mini player appears on tap rather than after the
        // starting item's artwork has been decoded.
        _currentSong.value = songs.getOrNull(startIndex)
        viewModelScope.launch { controller.playQueue(songs, startIndex) }
    }

    fun next() = controller.next()

    fun previous() = controller.previous()

    fun setShuffleEnabled(enabled: Boolean) = controller.setShuffleEnabled(enabled)

    fun setRepeatMode(mode: RepeatMode) = controller.setRepeatMode(mode)

    fun playNext(song: Song) = controller.playNext(song)

    fun addToQueue(song: Song) = controller.addToQueue(song)

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
