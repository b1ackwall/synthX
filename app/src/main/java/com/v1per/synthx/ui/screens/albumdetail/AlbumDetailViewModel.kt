package com.v1per.synthx.ui.screens.albumdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.LoadError
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Everything the album detail screen draws. [songs] is the list as displayed, in album order,
 * missing songs included. [playableSongCount] and [playableDurationMs] cover only songs that are
 * not missing — the ones Play and Shuffle would actually queue. [coverUri] is the compressed art of
 * the first song in that order that has any, missing or not (the art file outlives the audio file),
 * or null when no song has art.
 */
data class AlbumDetailData(
    val songs: List<Song>,
    val playableSongCount: Int,
    val playableDurationMs: Long,
    val coverUri: String?,
)

internal fun albumDetailData(songs: List<Song>): AlbumDetailData {
    val playable = songs.filterNot { it.isMissing }
    return AlbumDetailData(
        songs = songs,
        playableSongCount = playable.size,
        playableDurationMs = playable.sumOf { it.duration },
        coverUri = songs.firstNotNullOfOrNull { it.albumArtUri },
    )
}

class AlbumDetailViewModel(
    private val repository: SongRepository,
    private val albumId: String,
    private val appContext: Context,
) : ViewModel() {

    // Zero songs is Empty; an album whose songs are all missing is Success with nothing playable.
    private val _uiState = MutableStateFlow<UiState<AlbumDetailData>>(UiState.Loading)
    val uiState: StateFlow<UiState<AlbumDetailData>> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getSongsForAlbum(albumId)
                .catch { e ->
                    _uiState.value = UiState.Error(LoadError.AlbumSongs.message(appContext))
                }
                .collect { songs ->
                    _uiState.value =
                        if (songs.isEmpty()) UiState.Empty else UiState.Success(data = albumDetailData(songs))
                }
        }
    }

    companion object {
        fun factory(context: Context, albumId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = SongRepositoryImpl.create(context)
                // Class<T> is erased; this factory only ever builds AlbumDetailViewModel.
                @Suppress("UNCHECKED_CAST")
                return AlbumDetailViewModel(repository, albumId, context.applicationContext) as T
            }
        }
    }
}
