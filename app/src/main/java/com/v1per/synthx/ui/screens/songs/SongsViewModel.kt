package com.v1per.synthx.ui.screens.songs

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

enum class SortOrder {
    RECENTLY_ADDED,
    TITLE,
    ARTIST,
    ALBUM,
}

class SongsViewModel(
    private val repository: SongRepository,
    private val playbackViewModel: PlaybackViewModel,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<ImmutableList<Song>>>(UiState.Loading)
    val uiState: StateFlow<UiState<ImmutableList<Song>>> = _uiState.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.RECENTLY_ADDED)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    val currentlyPlayingSongId: StateFlow<String?> = playbackViewModel.currentSongId

    init {
        observeSongs()
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    private fun observeSongs() {
        viewModelScope.launch {
            combine(_sortOrder, repository.getSongs()) { order, songs ->
                songs.sorted(order).toPersistentList()
            }
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    _uiState.value = UiState.Error(LoadError.Songs.message(appContext))
                }
                .collect { songs ->
                    _uiState.value =
                        if (songs.isEmpty()) UiState.Empty else UiState.Success(data = songs)
                }
        }
    }

    private fun List<Song>.sorted(order: SortOrder): List<Song> = when (order) {
        SortOrder.RECENTLY_ADDED -> sortedByDescending { it.dateAdded }
        SortOrder.TITLE -> sortedByLowercase { it.title }
        SortOrder.ARTIST -> sortedByLowercase { it.artist }
        SortOrder.ALBUM -> sortedByLowercase { it.album }
    }

    // sortedBy { selector(it).lowercase() } re-invokes the selector on both operands of
    // every comparison (compareBy semantics) — O(n log n) String.lowercase() allocations.
    // Precomputing the key once per element and sorting by that cached value instead keeps
    // the exact same ordering (still a natural-order sort over the same lowercase strings)
    // for O(n) allocations.
    private inline fun List<Song>.sortedByLowercase(crossinline selector: (Song) -> String): List<Song> =
        map { it to selector(it).lowercase() }
            .sortedBy { it.second }
            .map { it.first }

    fun onSongTap(song: Song) {
        // The queue is the list as currently sorted and shown, so the tapped song's
        // neighbours play in the order the user can see.
        val songs = (_uiState.value as? UiState.Success)?.data ?: listOf(song)
        val index = songs.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: 0
        playbackViewModel.requestPlayQueue(songs, index)
    }

    class Factory(private val context: Context, private val playbackViewModel: PlaybackViewModel) :
        ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SongsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return SongsViewModel(
                SongRepositoryImpl.create(context),
                playbackViewModel,
                context.applicationContext,
            ) as T
        }
    }
}
