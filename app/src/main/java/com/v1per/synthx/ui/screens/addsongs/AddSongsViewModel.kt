package com.v1per.synthx.ui.screens.addsongs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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

    /** Typed rather than a raw message: the screen resolves app-voice copy via stringResource,
     *  so a stray exception message never reaches the UI verbatim. */
    enum class AddSongsError { LoadSongsFailed, LoadPlaylistFailed, SaveFailed }

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

    private val _error = MutableStateFlow<AddSongsError?>(null)
    val error: StateFlow<AddSongsError?> = _error.asStateFlow()

    // True until the first song-list emission (or a load failure) lands.
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Guards submit() against a double-tap firing setPlaylistSongs twice while the first
    // write is still in flight.
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                // Load existing playlist song IDs once
                val existingIds = repository.getSongIdsForPlaylist(playlistId).toSet()
                _checkedIds.value = existingIds

                // Collect live song list — checkbox state is independent
                repository.getSongs()
                    .catch { e ->
                        _error.value = AddSongsError.LoadSongsFailed
                        _isLoading.value = false
                    }
                    .collect { allSongs ->
                        _allSongs.value = allSongs
                        _error.value = null
                        _isLoading.value = false
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = AddSongsError.LoadPlaylistFailed
                _isLoading.value = false
            }
        }
    }

    fun toggleSong(songId: String) {
        _checkedIds.update { current ->
            if (songId in current) current - songId else current + songId
        }
    }

    fun submit() {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                val checkedIds = _checkedIds.value.toList()
                repository.setPlaylistSongs(playlistId, checkedIds)
                _error.value = null
                _submitted.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = AddSongsError.SaveFailed
            } finally {
                _isSubmitting.value = false
            }
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
