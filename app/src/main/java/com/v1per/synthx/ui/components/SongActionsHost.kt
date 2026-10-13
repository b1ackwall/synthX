package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.KnownAudioRoot
import com.v1per.synthx.playback.QueueAddResult
import kotlinx.coroutines.launch

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
    // business reaching playback. The `force` argument mirrors
    // PlaybackViewModel.playNext/addToQueue's own — false on the first attempt, true when
    // retrying after the user answers a duplicate-position confirmation below.
    onPlayNext: (Song, Boolean) -> QueueAddResult,
    onAddToQueue: (Song, Boolean) -> QueueAddResult,
    // The currently playing song's content id, or null when nothing is playing — just
    // enough playback state to hide the Play Next row for the current song (see
    // SongActionsBottomSheet's isCurrentlyPlaying) without giving this host a full
    // PlaybackViewModel reference.
    currentSongId: String?,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SongActionsViewModel = viewModel(
        factory = SongActionsViewModel.Factory(context),
    )
    val playlists by viewModel.playlists.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val deleteSongsEnabled by viewModel.deleteSongsEnabled.collectAsState()
    val deleteInProgress by viewModel.deleteInProgress.collectAsState()
    val removeFromStarredEnabled by viewModel.removeFromStarredEnabled.collectAsState()
    val graduationInProgress by viewModel.graduationInProgress.collectAsState()
    val playlistMemberships by viewModel.playlistMemberships.collectAsState()
    val deletePreview by viewModel.deletePreview.collectAsState()

    val deleteCleanupUncertainMessage by rememberUpdatedState(
        stringResource(R.string.actions_delete_cleanup_uncertain),
    )
    val musicGrantMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_music_grant_not_writable),
    )
    val newArrivalsGrantMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_new_arrivals_grant_not_writable),
    )
    val documentNotWritableMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_document_not_writable),
    )
    val identityMismatchMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_identity_mismatch),
    )
    val rootUnknownMessage by rememberUpdatedState(
        stringResource(R.string.destructive_file_root_unknown),
    )
    // context.getString rather than stringResource: the collect lambda is not a composable
    // scope, and the playlist name is only known at emit time.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SongActionEvent.AlreadyInPlaylist ->
                    context.getString(R.string.actions_already_in_playlist, event.playlistName)
                is SongActionEvent.AddedToPlaylist ->
                    context.getString(R.string.actions_added_to_playlist, event.playlistName)
                is SongActionEvent.RemovedFromPlaylist ->
                    context.getString(R.string.actions_removed_from_playlist, event.playlistName)
                SongActionEvent.RemovedFromStarred ->
                    context.getString(R.string.actions_removed_from_starred)
                SongActionEvent.ActionFailed -> context.getString(R.string.actions_failed)
                is SongActionEvent.DeletedButFileRemained ->
                    context.getString(R.string.actions_delete_file_remained)
                is SongActionEvent.DeletedButCleanupUncertain ->
                    deleteCleanupUncertainMessage
                SongActionEvent.AudioCleanupRetrySucceeded ->
                    context.getString(R.string.audio_cleanup_retry_succeeded)
                is SongActionEvent.DeleteBlocked -> when (val reason = event.reason) {
                    is DestructiveFilePreflightResult.GrantNotWritable -> when (reason.root) {
                        KnownAudioRoot.Music -> musicGrantMessage
                        KnownAudioRoot.NewArrivals -> newArrivalsGrantMessage
                    }
                    DestructiveFilePreflightResult.DocumentNotWritable -> documentNotWritableMessage
                    DestructiveFilePreflightResult.IdentityMismatch -> identityMismatchMessage
                    DestructiveFilePreflightResult.RootUnknown -> rootUnknownMessage
                }
            }
            val retryPath = when (event) {
                is SongActionEvent.DeletedButFileRemained -> event.filePath
                is SongActionEvent.DeletedButCleanupUncertain -> event.filePath
                else -> null
            }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = retryPath?.let { context.getString(R.string.action_retry) },
                duration = if (retryPath != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && retryPath != null) {
                viewModel.retryAudioFileDeletion(retryPath)
            }
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

    // Success feedback for playNext/addToQueue lives here rather than on viewModel.events:
    // both actions are answered by PlaybackViewModel, not SongActionsViewModel (see
    // onPlayNext/onAddToQueue's kdoc above), so there is no event for it to ride on.
    // Called from both the immediate-success path and the duplicate-position dialog's
    // confirm path below, since QueueAddResult.Added can arrive from either.
    fun showQueueSnackbar(messageRes: Int) {
        scope.launch { snackbarHostState.showSnackbar(context.getString(messageRes)) }
    }

    val controller = remember {
        SongActionsController { song, playlistId, source ->
            targetKey = SongActionsTargetKey(song.id, playlistId, source)
        }
    }

    // Holds the tapped song while its duplicate-position confirmation dialog is up —
    // null the rest of the time. Local state, same pattern as SongActionsBottomSheet's own
    // showDeleteConfirm, rather than a SharedFlow: both dialogs are answered from right here.
    var playNextConfirmTarget by remember { mutableStateOf<Song?>(null) }
    var addToQueueConfirmTarget by remember { mutableStateOf<Song?>(null) }
    // Song + playlist captured together at tap time: the sheet's own target is cleared by
    // dismiss() before this dialog shows.
    var removeFromPlaylistTarget by remember { mutableStateOf<RemoveFromPlaylistTarget?>(null) }

    // The ≥1-playlist-membership gate is resolved once per sheet opening, keyed on the
    // target — not per row and not per scroll frame. Rows never run this query; only the
    // single sheet instance does, and only when it actually opens on a Starred row.
    LaunchedEffect(target?.song?.id, target?.source) {
        viewModel.resolveRemoveFromStarredGate(
            songId = target?.song?.id?.takeIf { target.source == SongActionSource.Starred },
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
            deleteInProgress = deleteInProgress,
            deletePreview = deletePreview,
            onLoadDeletePreview = viewModel::loadDeletePreview,
            showRemoveFromStarredRow = current.source == SongActionSource.Starred,
            removeFromStarredEnabled = removeFromStarredEnabled && !graduationInProgress,
            isCurrentlyPlaying = current.song.id == currentSongId,
            onDeleteSong = {
                val id = current.song.id
                val filePath = current.song.filePath
                dismiss { viewModel.deleteSong(id, filePath) }
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
                    when (onPlayNext(song, false)) {
                        QueueAddResult.Added -> showQueueSnackbar(R.string.actions_playing_next)
                        QueueAddResult.NeedsConfirmation -> playNextConfirmTarget = song
                        // The engine already surfaced the scaffold's error snackbar.
                        QueueAddResult.Unavailable -> Unit
                    }
                }
            },
            onAddToQueue = {
                val song = current.song
                dismiss {
                    when (onAddToQueue(song, false)) {
                        QueueAddResult.Added -> showQueueSnackbar(R.string.actions_added_to_queue)
                        QueueAddResult.NeedsConfirmation -> addToQueueConfirmTarget = song
                        QueueAddResult.Unavailable -> Unit
                    }
                }
            },
            onRemoveFromPlaylist = {
                val playlistId = current.playlistId
                val song = current.song
                dismiss {
                    if (playlistId != null) {
                        removeFromPlaylistTarget = RemoveFromPlaylistTarget(song, playlistId)
                    }
                }
            },
            onRemoveFromStarred = {
                val songId = current.song.id
                dismiss { viewModel.removeFromStarred(songId) }
            },
            onDismiss = { dismiss() },
            playlistMemberships = playlistMemberships,
            onPlaylistsPageOpened = { viewModel.resolvePlaylistMemberships(current.song.id) },
        )
    }

    // Independent of the sheet/target above — the sheet is already dismissed by the time this
    // shows, and it must survive the sheet's own composable being torn down.
    val graduationStage by viewModel.graduationStage.collectAsState()
    graduationStage?.let { stage ->
        GraduationProgressDialog(stage = stage, onDismiss = viewModel::dismissGraduationDialog)
    }

    // Same independence from the sheet as the graduation dialog above — the sheet that
    // triggered these has already closed by the time either can show.
    playNextConfirmTarget?.let { song ->
        PlayNextDuplicateDialog(
            onConfirm = {
                if (onPlayNext(song, true) == QueueAddResult.Added) {
                    showQueueSnackbar(R.string.actions_playing_next)
                }
                playNextConfirmTarget = null
            },
            onDismiss = { playNextConfirmTarget = null },
        )
    }
    removeFromPlaylistTarget?.let { pending ->
        RemoveFromPlaylistDialog(
            songTitle = pending.song.title,
            playlistName = playlists.firstOrNull { it.id == pending.playlistId }?.name.orEmpty(),
            onConfirm = {
                viewModel.removeFromPlaylist(pending.playlistId, pending.song.id)
                removeFromPlaylistTarget = null
            },
            onDismiss = { removeFromPlaylistTarget = null },
        )
    }
    addToQueueConfirmTarget?.let { song ->
        AddToQueueDuplicateDialog(
            onConfirm = {
                if (onAddToQueue(song, true) == QueueAddResult.Added) {
                    showQueueSnackbar(R.string.actions_added_to_queue)
                }
                addToQueueConfirmTarget = null
            },
            onDismiss = { addToQueueConfirmTarget = null },
        )
    }
}

private data class RemoveFromPlaylistTarget(val song: Song, val playlistId: String)

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
