package com.v1per.synthx.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.common.launchCatching
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long the graduation progress dialog stays up after reaching a terminal stage before
 *  auto-dismissing, unless the user closes it manually first — see
 *  [SongActionsViewModel.scheduleGraduationAutoDismiss]. */
private const val GraduationDialogAutoDismissMs = 3_000L

private const val TAG = "SongActionsHost"

/** Where the sheet was opened from — lets the sheet tailor itself per origin. */
enum class SongActionSource { Default, Player, Starred }

/** Opens the song actions sheet from anywhere inside [SongActionsHost]. */
fun interface SongActionsController {
    fun show(song: Song, currentPlaylistId: String?, source: SongActionSource)
}

/** Outcomes worth telling the user about, surfaced as a Snackbar by [SongActionsHost]. */
sealed class SongActionEvent {
    data class AlreadyInPlaylist(val playlistName: String) : SongActionEvent()
    data class AddedToPlaylist(val playlistName: String) : SongActionEvent()
    data object PlayingNext : SongActionEvent()
    data object AlreadyPlayingNext : SongActionEvent()
    data object AddedToQueue : SongActionEvent()
    data object AlreadyInQueue : SongActionEvent()
    data object RemovedFromStarred : SongActionEvent()
    /** A mutation threw (Room/SAF failure) — see [SongActionsViewModel.launchCatching] use. */
    data object ActionFailed : SongActionEvent()
}

/**
 * SongRow appears on five screens, and the actions sheet needs navigation, a repository
 * and its own sheet state. Threading all of that through five call sites (and their
 * ViewModels) would mean the same five callbacks drilled through every screen that
 * happens to render a list of songs. Instead the sheet is hosted once, above the
 * NavHost, and rows reach it through composition.
 *
 * Defaults to a no-op so previews and tests can render SongRow without a host.
 */
val LocalSongActions: ProvidableCompositionLocal<SongActionsController> =
    compositionLocalOf { SongActionsController { _, _, _ -> } }

/**
 * Owns the single SongActionsBottomSheet instance and provides [LocalSongActions] to
 * [content]. Place inside whatever composable owns the NavController, so the navigation
 * callbacks can be supplied here rather than plumbed down.
 */
@Composable
fun SongActionsHost(
    snackbarHostState: SnackbarHostState,
    onNavigateToAlbum: (albumId: String, albumTitle: String) -> Unit,
    onNavigateToArtist: (artistId: String, artistName: String) -> Unit,
    // Supplied by the caller for the same reason the navigation callbacks are: the host
    // sits below whatever owns the PlaybackViewModel, and SongActionsViewModel has no
    // business reaching playback.
    onPlayNext: (Song) -> Boolean,
    onAddToQueue: (Song) -> Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SongActionsViewModel = viewModel(
        factory = SongActionsViewModel.Factory(context),
    )
    val playlists by viewModel.playlists.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val deleteSongsEnabled by viewModel.deleteSongsEnabled.collectAsState()
    val removeFromStarredEnabled by viewModel.removeFromStarredEnabled.collectAsState()

    // context.getString rather than stringResource: the collect lambda is not a composable
    // scope, and the playlist name is only known at emit time.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SongActionEvent.AlreadyInPlaylist ->
                    context.getString(R.string.actions_already_in_playlist, event.playlistName)
                is SongActionEvent.AddedToPlaylist ->
                    context.getString(R.string.actions_added_to_playlist, event.playlistName)
                SongActionEvent.PlayingNext -> context.getString(R.string.actions_playing_next)
                SongActionEvent.AlreadyPlayingNext ->
                    context.getString(R.string.actions_already_playing_next)
                SongActionEvent.AddedToQueue -> context.getString(R.string.actions_added_to_queue)
                SongActionEvent.AlreadyInQueue ->
                    context.getString(R.string.actions_already_in_queue)
                SongActionEvent.RemovedFromStarred ->
                    context.getString(R.string.actions_removed_from_starred)
                SongActionEvent.ActionFailed -> context.getString(R.string.actions_failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // Only the identifying key survives a configuration change — the sheet's target Song
    // isn't directly Saveable (it's a domain object, not Parcelable), so the full
    // SongActionsTarget is re-resolved below from `songs` (already-warm, process-lifetime
    // cached StateFlow — see SongRepositoryImpl.songsFlow) by id instead of being saved
    // directly. If the song can no longer be found post-restore (e.g. deleted while the
    // sheet was open across the config change) `target` below resolves to null and the
    // sheet simply doesn't render, rather than crashing on a stale reference.
    var targetKey by rememberSaveable(stateSaver = SongActionsTargetKeySaver) {
        mutableStateOf<SongActionsTargetKey?>(null)
    }
    val target = targetKey?.let { key ->
        songs.firstOrNull { it.id == key.songId }
            ?.let { song -> SongActionsTarget(song, key.playlistId, key.source) }
    }
    val sheetState = rememberRefinedBottomSheetState()
    val scope = rememberCoroutineScope()

    // Animate out, then drop from composition — clearing the flag first would snap it shut.
    fun dismiss(after: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            targetKey = null
            after()
        }
    }

    val controller = remember {
        SongActionsController { song, playlistId, source ->
            targetKey = SongActionsTargetKey(song.id, playlistId, source)
        }
    }

    // The ≥1-playlist-membership gate is resolved once per sheet opening, keyed on the
    // target — not per row and not per scroll frame. Rows never run this query; only the
    // single sheet instance does, and only when it actually opens on a Starred row.
    LaunchedEffect(target?.song?.id, target?.source) {
        viewModel.resolveRemoveFromStarredGate(
            songId = target?.song?.id?.takeIf { target?.source == SongActionSource.Starred },
        )
    }

    CompositionLocalProvider(LocalSongActions provides controller) {
        // Excluded from TalkBack traversal while the sheet is open — see
        // hiddenFromAccessibilityWhileSheetShown's kdoc in RefinedBottomSheet.kt.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hiddenFromAccessibilityWhileSheetShown(hidden = target != null),
        ) {
            content()
        }
    }

    target?.let { current ->
        SongActionsBottomSheet(
            song = current.song,
            sheetState = sheetState,
            playlists = playlists,
            currentPlaylistId = current.playlistId,
            showDeleteRow = current.source != SongActionSource.Player,
            deleteEnabled = deleteSongsEnabled,
            showRemoveFromStarredRow = current.source == SongActionSource.Starred,
            removeFromStarredEnabled = removeFromStarredEnabled,
            onDeleteSong = {
                val id = current.song.id
                dismiss { viewModel.deleteSong(id) }
            },
            // The detail routes take a display name for their top bar; the song already
            // carries both, so callers never have to look them up.
            onGoToAlbum = { albumId ->
                val title = current.song.album
                dismiss { onNavigateToAlbum(albumId, title) }
            },
            onGoToArtist = { artistId ->
                val name = current.song.artists.firstOrNull { it.id == artistId }?.name.orEmpty()
                dismiss { onNavigateToArtist(artistId, name) }
            },
            onAddToPlaylist = { playlistId ->
                val songId = current.song.id
                dismiss { viewModel.addToPlaylist(playlistId, songId) }
            },
            onPlayNext = {
                val song = current.song
                dismiss {
                    val added = onPlayNext(song)
                    viewModel.confirmPlayNext(added)
                }
            },
            onAddToQueue = {
                val song = current.song
                dismiss {
                    val added = onAddToQueue(song)
                    viewModel.confirmAddToQueue(added)
                }
            },
            onRemoveFromPlaylist = {
                val playlistId = current.playlistId
                val songId = current.song.id
                dismiss { if (playlistId != null) viewModel.removeFromPlaylist(playlistId, songId) }
            },
            onRemoveFromStarred = {
                val songId = current.song.id
                dismiss { viewModel.removeFromStarred(songId) }
            },
            onDismiss = { dismiss() },
        )
    }

    // Independent of the sheet/target above — the sheet is already dismissed by the time this
    // shows, and it must survive the sheet's own composable being torn down.
    val graduationStage by viewModel.graduationStage.collectAsState()
    graduationStage?.let { stage ->
        GraduationProgressDialog(stage = stage, onDismiss = viewModel::dismissGraduationDialog)
    }
}

private data class SongActionsTarget(
    val song: Song,
    val playlistId: String?,
    val source: SongActionSource,
)

/** [SongActionsTarget] reduced to primitives — what actually gets saved across config change. */
private data class SongActionsTargetKey(
    val songId: String,
    val playlistId: String?,
    val source: SongActionSource,
)

// Nullable Original (the sheet is usually closed) — an empty list is the null sentinel, since
// a saved value can't itself be null.
private val SongActionsTargetKeySaver: Saver<SongActionsTargetKey?, List<String?>> = Saver(
    save = { value: SongActionsTargetKey? ->
        value?.let { listOf(it.songId, it.playlistId, it.source.name) } ?: emptyList()
    },
    restore = { saved: List<String?> ->
        saved.takeIf { it.isNotEmpty() }
            ?.let { SongActionsTargetKey(it[0]!!, it[1], SongActionSource.valueOf(it[2]!!)) }
    },
)

class SongActionsViewModel(
    private val repository: SongRepository,
    private val preferences: PreferencesManager,
) : ViewModel() {

    val playlists: StateFlow<ImmutableList<Playlist>> = repository.getAllPlaylists()
        .map { it.toPersistentList() }
        .catch { emit(persistentListOf()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    // Only consulted to re-resolve a saved SongActionsTargetKey's songId back into a Song
    // after a configuration change (see SongActionsHost) — repository.getSongs() is itself
    // an already-warm, process-lifetime cached StateFlow (SongRepositoryImpl.songsFlow), so
    // this adds no extra query cost.
    val songs: StateFlow<ImmutableList<Song>> = repository.getSongs()
        .map { it.toPersistentList() }
        .catch { emit(persistentListOf()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    /** Gates the delete row in the sheet below — see [DeleteSongsGate]. */
    val deleteSongsEnabled: StateFlow<Boolean> = DeleteSongsGate.deleteSongsEnabled

    private val _removeFromStarredEnabled = MutableStateFlow(false)

    /**
     * Gates the "Remove from Starred" row: enabled only once the song belongs to at least
     * one playlist. Unlike [deleteSongsEnabled] this is per-song, so it is recomputed each
     * time the sheet opens — see [resolveRemoveFromStarredGate].
     */
    val removeFromStarredEnabled: StateFlow<Boolean> = _removeFromStarredEnabled.asStateFlow()

    // The songId a launched lookup was resolving for, so a late result can tell it's no
    // longer wanted even in the (should-be-impossible) case it runs after cancellation.
    private var starredGateSongId: String? = null
    private var starredGateJob: Job? = null

    /**
     * Resolves the gate above for the sheet's current target. [songId] is null whenever the
     * sheet is closed or was opened from anywhere but the Starred screen, which closes the
     * gate — so a stale `true` from a previous song can never leak into the next sheet.
     */
    fun resolveRemoveFromStarredGate(songId: String?) {
        // Closed first, then re-opened by the query result: the suspend call below lands a
        // frame or more later, and the row must not be tappable in the meantime.
        _removeFromStarredEnabled.value = false
        // Cancel any lookup still in flight for a previous song — without this, an older
        // song's isInAnyPlaylist result can land after a newer one's and clobber it.
        starredGateJob?.cancel()
        starredGateSongId = songId
        if (songId == null) return
        starredGateJob = viewModelScope.launch {
            val result = repository.isInAnyPlaylist(songId)
            // Defense in depth on top of the cancellation above: only apply the result if
            // this is still the song the gate was last asked to resolve.
            if (starredGateSongId == songId) {
                _removeFromStarredEnabled.value = result
            }
        }
    }

    // One-off UI events, matching LibraryViewModel's NavigationEvent and the playback
    // error flow: buffered by one, newest wins, never replayed to a new collector.
    private val _events = MutableSharedFlow<SongActionEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SongActionEvent> = _events.asSharedFlow()

    fun deleteSong(songId: String) {
        launchCatching(onError = { _events.tryEmit(SongActionEvent.ActionFailed) }) {
            repository.deleteSong(songId)
        }
    }

    fun addToPlaylist(playlistId: String, songId: String) {
        launchCatching(onError = { _events.tryEmit(SongActionEvent.ActionFailed) }) {
            val added = repository.addSongToPlaylist(playlistId, songId)
            val name = playlists.value.firstOrNull { it.id == playlistId }?.name.orEmpty()
            _events.tryEmit(
                if (added) SongActionEvent.AddedToPlaylist(name)
                else SongActionEvent.AlreadyInPlaylist(name)
            )
        }
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        launchCatching(onError = { _events.tryEmit(SongActionEvent.ActionFailed) }) {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    private val _graduationStage = MutableStateFlow<GraduationStage?>(null)

    /** Drives [GraduationProgressDialog] — null means no graduation is in flight or shown. */
    val graduationStage: StateFlow<GraduationStage?> = _graduationStage.asStateFlow()

    // Guards the delayed auto-dismiss below the same way LibraryManagementViewModel's
    // showTemporarily does: a manual dismissGraduationDialog() (or a newer graduation
    // starting) must be able to cancel a still-pending delayed clear outright, rather than
    // relying on a value-equality check racing a brand new terminal stage.
    private var graduationAutoDismissJob: Job? = null

    /**
     * Physically moves the song's file from wherever it currently lives into the configured
     * root library folder — copy, hash-verify, delete original — then updates its DB row,
     * clearing the starred flag as a side effect of the move landing. Replaces the old
     * dismiss-only behavior for this same button. Progress is reported via [graduationStage]
     * for [GraduationProgressDialog] to render.
     */
    fun removeFromStarred(songId: String) {
        val rootUri = preferences.musicFolderUri?.let { uri -> runCatching { Uri.parse(uri) }.getOrNull() }
        if (rootUri == null) {
            _events.tryEmit(SongActionEvent.ActionFailed)
            return
        }
        graduationAutoDismissJob?.cancel()
        viewModelScope.launch {
            repository.graduateSong(songId, rootUri)
                .catch { e ->
                    Log.e(TAG, "Graduation flow threw", e)
                    emit(GraduationStage.Failed("Something went wrong. Try again."))
                }
                .collect { stage ->
                    _graduationStage.value = stage
                    when (stage) {
                        GraduationStage.Done -> {
                            _events.tryEmit(SongActionEvent.RemovedFromStarred)
                            scheduleGraduationAutoDismiss()
                        }
                        is GraduationStage.Failed -> scheduleGraduationAutoDismiss()
                        else -> Unit
                    }
                }
        }
    }

    /** Auto-dismisses the dialog [GraduationDialogAutoDismissMs] after a terminal stage,
     *  unless [dismissGraduationDialog] (or a newer graduation) cancels this job first. */
    private fun scheduleGraduationAutoDismiss() {
        graduationAutoDismissJob = viewModelScope.launch {
            delay(GraduationDialogAutoDismissMs)
            _graduationStage.value = null
        }
    }

    /** Manual close — cancels any pending auto-dismiss so it can't fire a beat later against
     *  whatever the dialog is showing by then (closed again, or a new graduation). */
    fun dismissGraduationDialog() {
        graduationAutoDismissJob?.cancel()
        _graduationStage.value = null
    }

    /**
     * The queue itself is driven by PlaybackViewModel, which this ViewModel deliberately
     * does not reach — the host supplies that call. These only put the confirmation on the
     * same event flow the playlist actions already use, so every sheet action reports back
     * through one channel.
     */
    fun confirmPlayNext(added: Boolean) {
        _events.tryEmit(if (added) SongActionEvent.PlayingNext else SongActionEvent.AlreadyPlayingNext)
    }

    fun confirmAddToQueue(added: Boolean) {
        _events.tryEmit(if (added) SongActionEvent.AddedToQueue else SongActionEvent.AlreadyInQueue)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SongActionsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return SongActionsViewModel(
                SongRepositoryImpl.create(context),
                PreferencesManager(context.applicationContext),
            ) as T
        }
    }
}
