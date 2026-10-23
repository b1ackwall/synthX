package com.v1per.synthx.ui.screens.playlistdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.LoadError
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Everything the detail screen draws. [playlist] is null only when this id is no longer in the
 * library (deleted or restored away while the screen was open); the cover then falls back to the
 * flat placeholder, the same as a playlist that has no generated art yet.
 *
 * [playableSongCount] and [playableDurationMs] cover only songs that are not missing — the ones
 * Play and Shuffle would actually queue.
 */
data class PlaylistDetailData(
    val playlist: Playlist?,
    val songs: List<Song>,
    val playableSongCount: Int,
    val playableDurationMs: Long,
)

internal fun playlistDetailData(playlist: Playlist?, songs: List<Song>): PlaylistDetailData {
    val playable = songs.filterNot { it.isMissing }
    return PlaylistDetailData(
        playlist = playlist,
        songs = songs,
        playableSongCount = playable.size,
        playableDurationMs = playable.sumOf { it.duration },
    )
}

class PlaylistDetailViewModel(
    private val repository: SongRepository,
    private val playlistId: String,
    private val appContext: Context,
    private val stringResource: (Int) -> String = { id -> appContext.getString(id) },
) : ViewModel() {

    // An empty playlist is still Success (with no songs) rather than UiState.Empty: the cover
    // belongs above the empty message, so the screen needs the playlist in that state too.
    private val _uiState = MutableStateFlow<UiState<PlaylistDetailData>>(UiState.Loading)
    val uiState: StateFlow<UiState<PlaylistDetailData>> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.getSongsForPlaylist(playlistId),
                playlistFlow(),
            ) { songs, playlist -> playlistDetailData(playlist, songs) }
                .catch { e ->
                    _uiState.value = UiState.Error(LoadError.PlaylistSongs.message(stringResource))
                }
                .collect { data -> _uiState.value = UiState.Success(data = data) }
        }
    }

    /**
     * This playlist out of [SongRepository.getAllPlaylists], so its art arrives through the same
     * combine as the songs and the screen is ready — cover and list together — in one step.
     *
     * getAllPlaylists() is a StateFlow seeded with an empty list before its first query lands.
     * That seed is "not loaded yet", not "no such playlist" (this screen is only reachable for a
     * playlist that exists, so the real list is never empty), and passing it through would
     * flash the placeholder before the art. It is dropped; every later emission, including one
     * that no longer contains this id, is real.
     */
    private fun playlistFlow() = repository.getAllPlaylists()
        .dropWhile { it.isEmpty() }
        .map { playlists -> playlists.firstOrNull { it.id == playlistId } }

    companion object {
        fun factory(context: Context, playlistId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    // Class<T> is erased; this factory only ever builds PlaylistDetailViewModel.
                    @Suppress("UNCHECKED_CAST")
                    return PlaylistDetailViewModel(repository, playlistId, context.applicationContext) as T
                }
            }
    }
}
