package com.v1per.synthx.ui.screens.library

import android.content.Context
import androidx.concurrent.futures.await as awaitFuture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.common.LIBRARY_SCAN_TAG
import com.v1per.synthx.ui.common.LoadError
import com.v1per.synthx.ui.common.NEW_ARRIVALS_TAG
import com.v1per.synthx.ui.common.ScanStatusStrings
import com.v1per.synthx.ui.common.scanStatusFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

private const val RecentSongsLimit = 20

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
    private val preferences: PreferencesManager = PreferencesManager(appContext),
    private val workManager: WorkManager = WorkManager.getInstance(appContext),
) : ViewModel() {

    // Status text comes from the shared pure helper in ui/common — this ViewModel still has zero
    // coupling to LibraryManagementViewModel's richer scan-status machinery.
    private val strings = ScanStatusStrings { id, args -> appContext.getString(id, *args) }

    // One-shot results of a pull-to-refresh, shown as a snackbar. Buffered so a result that lands
    // while the screen is between compositions is delivered on resubscribe.
    private val _refreshMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val refreshMessages: SharedFlow<String> = _refreshMessages.asSharedFlow()

    // Replay is 0: a click emitted while no collector is running is dropped. The one-slot
    // buffer only serves an active collector, and DROP_OLDEST discards the older event.
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

    // Drives the pull-to-refresh spinner: true while any arrivals or main-root scan is unfinished
    // under the shared unique name — whichever of them the pull started, or another trigger point
    // (Settings rescan, folder-picker save, launch chain) did. A request KEEP discarded owns no work
    // of its own, so it never holds this true; if another scan is what's running, that scan is what
    // the spinner honestly shows, and [refreshMessages] says the pull itself was not started.
    val isRefreshing: StateFlow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
        .map { infos ->
            infos.any {
                (NEW_ARRIVALS_TAG in it.tags || LIBRARY_SCAN_TAG in it.tags) && !it.state.isFinished
            }
        }
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
     * Pull-to-refresh: scans New Arrivals when that folder is configured, otherwise the main music
     * root — an arrivals scan with no arrivals folder is guaranteed to fail, so it would only ever
     * look like a silent no-op. Not "always scan both": each gesture starts exactly one scan.
     *
     * KEEP either way: never important enough to interrupt a scan already running under the
     * shared unique name. Whether KEEP honoured the request is decided exactly, not guessed from
     * "is something else running": await the enqueue operation, then ask for *this request's*
     * id — a discarded request was never inserted, so it resolves to no WorkInfo. That is
     * reported distinctly ("already scanning") from a scan that ran and failed. The outcome of a
     * scan that did start is then surfaced through [refreshMessages] using the shared status text.
     */
    fun onPullToRefresh() {
        viewModelScope.launch {
            try {
                val enqueued = if (preferences.newArrivalsFolderUri != null) {
                    NewArrivalsScanWorker.enqueue(appContext, ExistingWorkPolicy.KEEP)
                } else {
                    LibraryScanWorker.enqueue(appContext, ExistingWorkPolicy.KEEP)
                }
                enqueued.operation.await()
                if (workManager.getWorkInfoById(enqueued.id).awaitFuture() == null) {
                    _refreshMessages.emit(appContext.getString(R.string.library_refresh_already_scanning))
                    return@launch
                }
                val finished = workManager.getWorkInfoByIdFlow(enqueued.id)
                    .filterNotNull()
                    .first { it.state.isFinished }
                scanStatusFor(finished, strings)?.let { _refreshMessages.emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _refreshMessages.emit(
                    appContext.getString(
                        R.string.scan_status_failed,
                        e.message ?: appContext.getString(R.string.scan_status_unknown_error),
                    ),
                )
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = SongRepositoryImpl.create(context)
                // Class<T> is erased; this factory only ever builds LibraryViewModel.
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(repository, context.applicationContext) as T
            }
        }
    }
}
