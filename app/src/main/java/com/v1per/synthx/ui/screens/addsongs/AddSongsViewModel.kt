package com.v1per.synthx.ui.screens.addsongs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddSongsViewModel(
    private val repository: SongRepository,
    private val playlistId: String,
) : ViewModel() {

    data class SongItem(
        val song: Song,
        val isChecked: Boolean,
    )

    private val _songs = MutableStateFlow<List<SongItem>>(emptyList())
    val songs: StateFlow<List<SongItem>> = _songs.asStateFlow()

    private val _submitted = MutableStateFlow(false)
    val submitted: StateFlow<Boolean> = _submitted.asStateFlow()

    init {
        viewModelScope.launch {
            val existingIds = repository.getSongIdsForPlaylist(playlistId).toSet()
            repository.getSongs()
                .collect { allSongs ->
                    _songs.value = allSongs.map { song ->
                        SongItem(song = song, isChecked = song.id in existingIds)
                    }
                }
        }
    }

    fun toggleSong(songId: String) {
        _songs.value = _songs.value.map { item ->
            if (item.song.id == songId) item.copy(isChecked = !item.isChecked) else item
        }
    }

    fun submit() {
        viewModelScope.launch {
            val checkedIds = _songs.value.filter { it.isChecked }.map { it.song.id }
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
