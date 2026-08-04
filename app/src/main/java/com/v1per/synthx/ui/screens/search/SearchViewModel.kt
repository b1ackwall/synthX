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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    enum class SearchFilter { ALL, SONGS, PLAYLISTS, ARTISTS, ALBUMS }

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
                .catch { e -> _error.value = "Search failed: ${e.message}" }
                .collect {
                    _results.value = it
                    _error.value = null
                }
        }
    }

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun onFilterChange(filter: SearchFilter) {
        _filter.value = filter
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return SearchViewModel(repository) as T
                }
            }
    }
}
