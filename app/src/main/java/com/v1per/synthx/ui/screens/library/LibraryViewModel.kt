package com.v1per.synthx.ui.screens.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.ui.common.LoadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

private const val RecentSongsLimit = 20

/** WorkManager tags every request with its worker's fully-qualified class name.
 *  Declared independently here rather than reused from LibraryManagementViewModel's
 *  own copy (which is file-private) — this ViewModel deliberately has zero
 *  cross-ViewModel coupling to that screen's richer scan-status machinery. */
private val NEW_ARRIVALS_TAG: String = NewArrivalsScanWorker::class.java.name

sealed class NavigationEvent {
    object GoToSongs : NavigationEvent()
    object GoToArtistList : NavigationEvent()
    object GoToAlbumList : NavigationEvent()
    object GoToPlaylistList : NavigationEvent()
    object GoToStarred : NavigationEvent()
}

class LibraryViewModel(
    private val repository: SongRepository,
    private val appContext: Context,
) : ViewModel() {

    // Buffered so a click emitted while the screen is between compositions
    // is delivered on resubscribe instead of being dropped.
    private val _navigationEvents = MutableSharedFlow<NavigationEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()

    private val _recentSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val recentSongs: StateFlow<ImmutableList<Song>> = _recentSongs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // True only until the first emission (success or failure) lands, so the Recently Added
    // section can distinguish "still loading" from "loaded and genuinely empty" — both used
    // to render as nothing.
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Badge on the Starred category row. WhileSubscribed rather than Lazily: unlike the
    // repository's library-wide caches this is only meaningful while the Library screen is
    // actually on screen. 0 as the initial value renders no badge, so the row never flashes
    // a wrong count before the first emission lands.
    val starredCount: StateFlow<Int> = repository.getStarredSongCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Drives the pull-to-refresh spinner only — quiet by design, no result surfaced here.
    // Independent of LibraryManagementViewModel's richer ScanStatus: reads WorkManager
    // directly so a scan started from any of the three trigger points (this pull, the
    // launch-time Verify->NewArrivals chain, or the folder-picker save) is reflected the
    // same way, without this screen knowing which one is actually running.
    val isRefreshing: StateFlow<Boolean> = WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
        .map { infos -> infos.any { NEW_ARRIVALS_TAG in it.tags && !it.state.isFinished } }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            repository.getRecentSongs(RecentSongsLimit)
                .map { it.toPersistentList() }
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    _error.value = LoadError.RecentlyAdded.message(appContext)
                    _isLoading.value = false
                }
                .collect {
                    _recentSongs.value = it
                    _error.value = null
                    _isLoading.value = false
                }
        }
    }

    fun onPlaylistsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToPlaylistList)
        }
    }

    fun onArtistsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToArtistList)
        }
    }

    fun onAlbumsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToAlbumList)
        }
    }

    fun onSongsClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToSongs)
        }
    }

    fun onStarredClick() {
        viewModelScope.launch {
            _navigationEvents.emit(NavigationEvent.GoToStarred)
        }
    }

    /**
     * Triggers a New Arrivals scan from a user pull-to-refresh gesture. KEEP per
     * [NewArrivalsScanWorker.enqueue]'s own kdoc default: this is never important enough
     * to interrupt a scan already running under the shared unique-work name (whether
     * that's the launch-time Verify->NewArrivals chain or a folder-picker save) — the
     * spinner just observes whatever is already in flight via [isRefreshing] instead.
     */
    fun onPullToRefresh() {
        NewArrivalsScanWorker.enqueue(appContext, ExistingWorkPolicy.KEEP)
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return LibraryViewModel(repository, context.applicationContext) as T
                }
            }
    }
}
