package com.v1per.synthx.ui.screens.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.LoadError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository: SongRepository,
    private val appContext: Context,
) : ViewModel() {

    enum class SearchFilter { ALL, SONGS, PLAYLISTS, ARTISTS, ALBUMS }

    /** Resolved to app-voice strings.xml copy by the screen — see
     *  GroupDetailViewModel.ActionError for the same pattern. */
    enum class ActionError { RenamePlaylistFailed, DeletePlaylistFailed }

    data class SearchResults(
        val songs: List<Song> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val albums: List<Album> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
    ) {
        val isEmpty
            get() = songs.isEmpty() && artists.isEmpty() &&
                albums.isEmpty() && playlists.isEmpty()
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.SONGS)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Flips true exactly once, on the first combine() emission below, and never back to
    // false for the rest of this ViewModel's lifetime — a re-collected flow (e.g. after
    // config change) must not re-cover already-shown content. Same one-shot discipline as
    // RefinedBottomSheetState's re-entry guard.
    private val _hasLoadedInitialData = MutableStateFlow(false)
    val hasLoadedInitialData: StateFlow<Boolean> = _hasLoadedInitialData.asStateFlow()

    // A failed rename/delete is a transient event, not a change to the search results —
    // see GroupDetailViewModel._actionErrors for the same reasoning.
    private val _actionErrors = MutableSharedFlow<ActionError>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actionErrors: SharedFlow<ActionError> = _actionErrors.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(
                _query.debounce(300),
                repository.getSongs(),
                repository.getArtists(),
                repository.getAlbums(),
                repository.getAllPlaylists(),
            ) { query, songs, artists, albums, playlists ->
                if (query.isBlank()) return@combine SearchResults()
                val q = query.trim().lowercase()
                SearchResults(
                    songs = songs.filter {
                        it.title.lowercase().contains(q) ||
                            it.artist.lowercase().contains(q)
                    },
                    artists = artists.filter { it.name.lowercase().contains(q) },
                    albums = albums.filter {
                        it.title.lowercase().contains(q) ||
                            it.artist.lowercase().contains(q)
                    },
                    playlists = playlists.filter { it.name.lowercase().contains(q) },
                )
            }
                .flowOn(Dispatchers.Default)
                .catch { e -> _error.value = LoadError.Search.message(appContext) }
                .collect {
                    _results.value = it
                    _error.value = null
                    _hasLoadedInitialData.value = true
                }
        }
    }

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun onFilterChange(filter: SearchFilter) {
        _filter.value = filter
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                repository.renamePlaylist(id, name.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionErrors.tryEmit(ActionError.RenamePlaylistFailed)
            }
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            try {
                repository.deletePlaylist(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionErrors.tryEmit(ActionError.DeletePlaylistFailed)
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return SearchViewModel(repository, context.applicationContext) as T
                }
            }
    }
}
