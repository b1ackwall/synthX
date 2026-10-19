package com.v1per.synthx.ui.components

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongDeletionOutcome
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.common.launchCatching
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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

/** How long the graduation progress dialog stays up after success before auto-dismissing,
 *  unless the user closes it manually first — see
 *  [SongActionsViewModel.scheduleGraduationAutoDismiss]. */
private const val GraduationDialogAutoDismissMs = 3_000L

internal const val GRADUATION_MUSIC_FOLDER_REQUIRED_REASON =
    "Choose a Music folder in Settings > Library Management before moving this song."

private const val TAG = "SongActionsHost"

class SongActionsViewModel(
    private val repository: SongRepository,
    private val preferences: PreferencesManager,
    private val graduationDismissDelay: suspend () -> Unit = { delay(GraduationDialogAutoDismissMs) },
) : ViewModel() {

    private val _deletePreview = MutableStateFlow<DeleteSongPreviewState>(DeleteSongPreviewState.Loading)
    val deletePreview: StateFlow<DeleteSongPreviewState> = _deletePreview.asStateFlow()
    private var deletePreviewJob: Job? = null

    fun loadDeletePreview(songId: String) {
        deletePreviewJob?.cancel()
        _deletePreview.value = DeleteSongPreviewState.Loading
        deletePreviewJob = viewModelScope.launch {
            _deletePreview.value = try {
                DeleteSongPreviewState.Ready(repository.previewDeleteSong(songId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                DeleteSongPreviewState.Failed
            }
        }
    }

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

    private val _playlistMemberships = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Ids of playlists the sheet's current song already belongs to — drives the
     * checkmark/disabled state on the Playlists sub-page. Resolved on demand (see
     * [resolvePlaylistMemberships]) rather than eagerly per sheet-open, since most sheet
     * opens never navigate to that sub-page.
     */
    val playlistMemberships: StateFlow<Set<String>> = _playlistMemberships.asStateFlow()

    // Same songId/Job-cancellation shape as starredGateSongId/starredGateJob above, so a
    // stale result for a previous song can't land after a newer lookup starts.
    private var membershipsSongId: String? = null
    private var membershipsJob: Job? = null

    /** Called when the Playlists sub-page is opened for [songId]. */
    fun resolvePlaylistMemberships(songId: String) {
        _playlistMemberships.value = emptySet()
        membershipsJob?.cancel()
        membershipsSongId = songId
        membershipsJob = viewModelScope.launch {
            val result = repository.getPlaylistIdsForSong(songId).toSet()
            if (membershipsSongId == songId) {
                _playlistMemberships.value = result
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

    private val _deleteInProgress = MutableStateFlow(false)

    /**
     * Guards against a double-tap invoking [deleteSong] twice — see [ReplaceSongViewModel][
     * com.v1per.synthx.ui.screens.replacesong.ReplaceSongViewModel]'s `InProgress` outcome for
     * the pattern this mirrors. A second call is inert for the row/file (Room delete is
     * idempotent), but without this it would still take a second automatic backup and record a
     * second [com.v1per.synthx.preferences.DestructiveActionTracker] entry.
     */
    val deleteInProgress: StateFlow<Boolean> = _deleteInProgress.asStateFlow()

    fun deleteSong(songId: String, filePath: String) {
        if (_deleteInProgress.value) return
        _deleteInProgress.value = true
        launchCatching(onError = { _events.tryEmit(SongActionEvent.ActionFailed) }) {
            try {
                // Silent on the ordinary success: the row vanishing from every list is its own
                // feedback. Physical cleanup warnings contain information the user could not
                // otherwise infer from the UI.
                when (val outcome = repository.deleteSong(songId)) {
                    SongDeletionOutcome.Deleted -> Unit
                    SongDeletionOutcome.FileRemained ->
                        _events.tryEmit(SongActionEvent.DeletedButFileRemained(filePath))
                    SongDeletionOutcome.CleanupUncertain ->
                        _events.tryEmit(SongActionEvent.DeletedButCleanupUncertain(filePath))
                    is SongDeletionOutcome.Blocked ->
                        _events.tryEmit(SongActionEvent.DeleteBlocked(outcome.reason))
                }
            } finally {
                _deleteInProgress.value = false
            }
        }
    }

    fun retryAudioFileDeletion(filePath: String) {
        launchCatching(onError = {
            _events.tryEmit(SongActionEvent.DeletedButCleanupUncertain(filePath))
        }) {
            when (repository.retryAudioFileDeletion(filePath)) {
                SongDeletionOutcome.Deleted ->
                    _events.tryEmit(SongActionEvent.AudioCleanupRetrySucceeded)
                SongDeletionOutcome.FileRemained ->
                    _events.tryEmit(SongActionEvent.DeletedButFileRemained(filePath))
                SongDeletionOutcome.CleanupUncertain ->
                    _events.tryEmit(SongActionEvent.DeletedButCleanupUncertain(filePath))
                is SongDeletionOutcome.Blocked ->
                    _events.tryEmit(SongActionEvent.DeletedButCleanupUncertain(filePath))
            }
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
            // After the call, not before: a throw lands in onError as ActionFailed instead.
            val name = playlists.value.firstOrNull { it.id == playlistId }?.name.orEmpty()
            _events.tryEmit(SongActionEvent.RemovedFromPlaylist(name))
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

    private val _graduationInProgress = MutableStateFlow(false)

    /**
     * True from the moment [removeFromStarred] accepts a graduation until its flow completes —
     * independent of [graduationStage], which the user can clear by closing the dialog while the
     * move is still running. Gates re-entry and the sheet's Remove from Starred row.
     */
    val graduationInProgress: StateFlow<Boolean> = _graduationInProgress.asStateFlow()

    /**
     * Physically moves the song's file from wherever it currently lives into the configured
     * root library folder — copy, hash-verify, delete original — then updates its DB row,
     * clearing the starred flag as a side effect of the move landing. Progress is reported via [graduationStage]
     * for [GraduationProgressDialog] to render.
     */
    fun removeFromStarred(songId: String) {
        // Re-entry guard, same shape as deleteInProgress. The progress dialog can be closed while
        // a graduation is still running and the song stays in Starred until its DB step lands, so
        // without this the sheet could launch a second graduation alongside the first.
        // FileOperationCoordinator's song lock would serialize them anyway; this keeps the second
        // from ever starting.
        if (_graduationInProgress.value) return
        val rootUri = preferences.musicFolderUri?.let { uri -> runCatching { uri.toUri() }.getOrNull() }
        if (rootUri == null) {
            graduationAutoDismissJob?.cancel()
            _graduationStage.value = GraduationStage.Failed(GRADUATION_MUSIC_FOLDER_REQUIRED_REASON)
            return
        }
        val newArrivalsRootUri = preferences.newArrivalsFolderUri
            ?.let { uri -> runCatching { uri.toUri() }.getOrNull() }
        _graduationInProgress.value = true
        graduationAutoDismissJob?.cancel()
        viewModelScope.launch {
            try {
                repository.graduateSong(songId, rootUri, newArrivalsRootUri)
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
                            // Recovery instructions remain visible until the dialog's Close action
                            // acknowledges them. Only ordinary success is transient.
                            is GraduationStage.Failed -> graduationAutoDismissJob?.cancel()
                            else -> Unit
                        }
                    }
            } finally {
                _graduationInProgress.value = false
            }
        }
    }

    /** Auto-dismisses the dialog [GraduationDialogAutoDismissMs] after successful graduation,
     *  unless [dismissGraduationDialog] (or a newer graduation) cancels this job first. */
    private fun scheduleGraduationAutoDismiss() {
        graduationAutoDismissJob = viewModelScope.launch {
            graduationDismissDelay()
            _graduationStage.value = null
        }
    }

    /** Manual close — cancels any pending auto-dismiss so it can't fire a beat later against
     *  whatever the dialog is showing by then (closed again, or a new graduation). */
    fun dismissGraduationDialog() {
        graduationAutoDismissJob?.cancel()
        _graduationStage.value = null
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
