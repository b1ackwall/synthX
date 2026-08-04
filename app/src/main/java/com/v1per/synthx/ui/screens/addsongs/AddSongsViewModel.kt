package com.v1per.synthx.ui.screens.addsongs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddSongsViewModel(
    private val repository: SongRepository,
    private val playlistId: String,
) : ViewModel() {

    data class SongItem(
        val song: Song,
        val isChecked: Boolean,
    )

    // Mutable set of checked song IDs — source of truth for checkbox state.
    // Kept separate from the song list so live library emissions can refresh
    // the list without discarding the user's in-progress selection.
    private val _checkedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())

    val songs: StateFlow<List<SongItem>> = combine(_allSongs, _checkedIds) { songs, checked ->
        songs.map { SongItem(song = it, isChecked = it.id in checked) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _submitted = MutableStateFlow(false)
    val submitted: StateFlow<Boolean> = _submitted.asStateFlow()

    init {
        viewModelScope.launch {
            // Load existing playlist song IDs once
            val existingIds = repository.getSongIdsForPlaylist(playlistId).toSet()
            _checkedIds.value = existingIds

            // Collect live song list — checkbox state is independent
            repository.getSongs().collect { allSongs ->
                _allSongs.value = allSongs
            }
        }
    }

    fun toggleSong(songId: String) {
        _checkedIds.update { current ->
            if (songId in current) current - songId else current + songId
        }
    }

    fun submit() {
        viewModelScope.launch {
            val checkedIds = _checkedIds.value.toList()
            repository.setPlaylistSongs(playlistId, checkedIds)
            _submitted.value = true
        }
    }

    companion object {
        fun factory(context: Context, playlistId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return AddSongsViewModel(repository, playlistId) as T
                }
            }
    }
}
