package com.v1per.synthx.ui.screens.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Album
import com.v1per.synthx.data.repository.Artist
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.screens.search.SearchViewModel.SearchFilter
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @Test
    fun defaultFilterIsAllAndManualSelectionSurvivesSearchActivity() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val repository = SearchRepositoryFlows()
            val viewModel = SearchViewModel(repository.instance, Application())
            try {
                assertEquals(SearchFilter.ALL, viewModel.filter.value)

                viewModel.onFilterChange(SearchFilter.SONGS)
                assertEquals(SearchFilter.SONGS, viewModel.filter.value)

                viewModel.onQueryChange("needle")
                repository.songs.value = listOf(
                    Song(
                        id = "song-id",
                        title = "Needle",
                        artist = "Artist",
                        album = "Album",
                        duration = 1L,
                        filePath = "song.mp3",
                    )
                )
                withTimeout(2_000) {
                    viewModel.results.first { results -> results.songs.isNotEmpty() }
                }

                assertEquals(SearchFilter.SONGS, viewModel.filter.value)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class SearchRepositoryFlows {
    val songs = MutableStateFlow<List<Song>>(emptyList())
    private val artists = MutableStateFlow<List<Artist>>(emptyList())
    private val albums = MutableStateFlow<List<Album>>(emptyList())
    private val playlists = MutableStateFlow<List<Playlist>>(emptyList())

    @Suppress("UNCHECKED_CAST")
    val instance: SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSongs" -> songs
            "getArtists" -> artists
            "getAlbums" -> albums
            "getAllPlaylists" -> playlists
            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository
}
