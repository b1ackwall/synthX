package com.v1per.synthx.ui.screens.starred

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.common.LoadError
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Songs not yet organized into a playlist. Deliberately the same shape as SongsViewModel,
 * minus sorting: the Starred list's order is fixed to newest-first in the DAO query, since
 * "what arrived and still needs filing" only has one useful reading order.
 */
class StarredViewModel(
    private val repository: SongRepository,
    private val playbackViewModel: PlaybackViewModel,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Song>>> = _uiState.asStateFlow()

    val currentlyPlayingSongId: StateFlow<String?> = playbackViewModel.currentSongId

    init {
        observeStarredSongs()
    }

    private fun observeStarredSongs() {
        viewModelScope.launch {
            repository.getStarredSongs()
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    _uiState.value = UiState.Error(LoadError.Starred.message(appContext))
                }
                .collect { songs ->
                    _uiState.value =
                        if (songs.isEmpty()) UiState.Empty else UiState.Success(data = songs)
                }
        }
    }

    fun onSongTap(song: Song) {
        // The queue is the list as currently shown, so the tapped song's neighbours play in
        // the order the user can see — same contract as SongsViewModel.onSongTap.
        val songs = (_uiState.value as? UiState.Success)?.data ?: listOf(song)
        val index = songs.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: 0
        playbackViewModel.requestPlayQueue(songs, index)
    }

    class Factory(
        private val context: Context,
        private val playbackViewModel: PlaybackViewModel,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(StarredViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return StarredViewModel(
                SongRepositoryImpl.create(context),
                playbackViewModel,
                context.applicationContext,
            ) as T
        }
    }
}
