package com.v1per.synthx.ui.screens.artistdetail

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
 * Everything the artist detail screen draws. [songs] is the list as displayed, missing songs
 * included. [playableSongCount] and [playableDurationMs] cover only songs that are not missing —
 * the ones Play and Shuffle would actually queue.
 */
data class ArtistDetailData(
    val songs: List<Song>,
    val playableSongCount: Int,
    val playableDurationMs: Long,
)

internal fun artistDetailData(songs: List<Song>): ArtistDetailData {
    val playable = songs.filterNot { it.isMissing }
    return ArtistDetailData(
        songs = songs,
        playableSongCount = playable.size,
        playableDurationMs = playable.sumOf { it.duration },
    )
}

class ArtistDetailViewModel(
    private val repository: SongRepository,
    private val artistId: String,
    private val appContext: Context,
) : ViewModel() {

    // Zero songs is Empty; an artist whose songs are all missing is Success with nothing playable.
    private val _uiState = MutableStateFlow<UiState<ArtistDetailData>>(UiState.Loading)
    val uiState: StateFlow<UiState<ArtistDetailData>> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getSongsForArtist(artistId)
                .catch { e ->
                    _uiState.value = UiState.Error(LoadError.ArtistSongs.message(appContext))
                }
                .collect { songs ->
                    _uiState.value =
                        if (songs.isEmpty()) UiState.Empty else UiState.Success(data = artistDetailData(songs))
                }
        }
    }

    companion object {
        fun factory(context: Context, artistId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return ArtistDetailViewModel(repository, artistId, context.applicationContext) as T
                }
            }
    }
}
