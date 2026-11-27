package com.v1per.synthx.ui.screens.replacesong

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.PlaylistConflictResolution
import com.v1per.synthx.data.repository.ReplaceSongPreview
import com.v1per.synthx.data.repository.ReplaceSongMembershipSnapshot
import com.v1per.synthx.data.repository.ReplaceSongPreviewResult
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.data.repository.ReplaceSongStage
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult
import com.v1per.synthx.ui.common.destructiveFileBlockMessage
import com.v1per.synthx.ui.common.searchKey
import com.v1per.synthx.ui.common.searchKeyed
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Steps of the Replace Song wizard. Modeled as state within one screen/route rather than separate
 * nav routes, for the same reasons [com.v1per.synthx.ui.screens.onboarding.OnboardingStep] is: the
 * flow is short, linear, and has nothing worth deep-linking to — and here also because a half-made
 * selection must not survive on a back stack for the user to return to out of context.
 */
enum class ReplaceSongStep { PickOld, PickNew, ReviewAndConfirm }

/**
 * The dry-run analysis shown before the destructive confirm, mirroring
 * [com.v1per.synthx.ui.screens.restore.RestorePreview]'s shape — including the part that matters
 * most: the songs the analysis was computed for live *inside* [Ready], carried by
 * [ReplaceSongPreview] itself, rather than being read from separate nullable fields at confirm
 * time. That is what makes it impossible to confirm one replacement against another one's preview.
 */
sealed class ReplaceSongPreviewState {
    data object Idle : ReplaceSongPreviewState()
    data class Loading(val oldSong: Song, val newSong: Song) : ReplaceSongPreviewState()
    data class Ready(val preview: ReplaceSongPreview, val membershipSnapshot: ReplaceSongMembershipSnapshot) :
        ReplaceSongPreviewState()
    data class Failed(val message: String, val retryable: Boolean) : ReplaceSongPreviewState()
}

/** Matches SearchViewModel's own query debounce — same in-memory filter over the same list. */
private const val QueryDebounceMs = 200L

private const val TAG = "ReplaceSongViewModel"

/**
 * Thin step-sequencer plus the two things a step alone cannot own: the filtered song list both
 * pickers render, and the preview/outcome state the review step confirms against. The actual
 * replacement is entirely [SongRepository.replaceSong]'s — nothing here reimplements any part of
 * the transplant, the deletion, or the live-queue handling.
 */
@OptIn(FlowPreview::class)
class ReplaceSongViewModel(
    private val repository: SongRepository,
    private val appContext: Context,
    private val stringResource: (Int) -> String = { id -> appContext.getString(id) },
) : ViewModel() {

    private val _step = MutableStateFlow(ReplaceSongStep.PickOld)
    val step: StateFlow<ReplaceSongStep> = _step.asStateFlow()

    private val _oldSong = MutableStateFlow<Song?>(null)

    /** Locked in by [selectOldSong]; cleared only by stepping back to [ReplaceSongStep.PickOld]. */
    val oldSong: StateFlow<Song?> = _oldSong.asStateFlow()

    private val _newSong = MutableStateFlow<Song?>(null)
    val newSong: StateFlow<Song?> = _newSong.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _preview = MutableStateFlow<ReplaceSongPreviewState>(ReplaceSongPreviewState.Idle)
    val preview: StateFlow<ReplaceSongPreviewState> = _preview.asStateFlow()

    private val _conflictResolution = MutableStateFlow(PlaylistConflictResolution.KeepOld)

    /** Only consulted for playlists both songs are in; see [PlaylistConflictResolution]. */
    val conflictResolution: StateFlow<PlaylistConflictResolution> = _conflictResolution.asStateFlow()

    private val _outcome = MutableStateFlow<ReplaceSongOutcome?>(null)
    val outcome: StateFlow<ReplaceSongOutcome?> = _outcome.asStateFlow()
    private val _cleanupRetryInProgress = MutableStateFlow(false)
    val cleanupRetryInProgress: StateFlow<Boolean> = _cleanupRetryInProgress.asStateFlow()

    private val _movingReplacement = MutableStateFlow(false)

    /** True while a committed replace graduates its New Arrivals replacement into Music. */
    val movingReplacement: StateFlow<Boolean> = _movingReplacement.asStateFlow()

    /**
     * What the current picker step should list.
     *
     * Reads [SongRepository.getSongs] — already a warm, process-lifetime cached StateFlow shared
     * with every other screen (see SongRepositoryImpl.songsFlow) — and filters it in memory, the
     * same substring match the Search screen uses. Deliberately not built on SearchViewModel
     * itself, which carries four result types, playlist rename/delete actions and a filter chip
     * state that a song picker has no use for.
     *
     * A blank query lists the whole library rather than nothing (unlike Search): this is a picker,
     * so scrolling to a song is a legitimate way to find it.
     */
    val results: StateFlow<ImmutableList<Song>> = combine(
        repository.getSongs().map { songs -> songs.searchKeyed({ it.title }, { it.artist }) },
        _query.debounce(QueryDebounceMs),
        _oldSong,
        _step,
    ) { songs, query, excluded, step ->
        val queryKey = searchKey(query.trim())
        songs.asSequence()
            .filter { keyed ->
                // Cached scan state keeps known-missing songs out of the replacement picker, and
                // the old song cannot replace itself. This is only a convenience filter: the
                // repository live-validates the selected replacement's document and content when
                // the user confirms. A missing song remains valid as the song being replaced.
                when (step) {
                    ReplaceSongStep.PickOld -> true
                    else -> !keyed.item.isMissing && keyed.item.id != excluded?.id
                }
            }
            .filter { keyed -> keyed.matches(queryKey) }
            .map { keyed -> keyed.item }
            .toPersistentList()
    }
        .flowOn(Dispatchers.Default)
        .catch { emit(persistentListOf()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun selectOldSong(song: Song) {
        _oldSong.value = song
        // Each step starts its own search rather than inheriting the previous one's — the two
        // songs are found by different terms in every realistic case.
        _query.value = ""
        _step.value = ReplaceSongStep.PickNew
    }

    fun selectNewSong(song: Song) {
        val old = _oldSong.value ?: return
        _newSong.value = song
        _query.value = ""
        _step.value = ReplaceSongStep.ReviewAndConfirm
        beginPreview(old, song)
    }

    /** Back out of [ReplaceSongStep.PickNew] — the old song stops being locked in. */
    fun backToPickOld() {
        _oldSong.value = null
        _newSong.value = null
        _query.value = ""
        _preview.value = ReplaceSongPreviewState.Idle
        _step.value = ReplaceSongStep.PickOld
    }

    /** Back out of the review step — the replacement stops being locked in, the old song stays. */
    fun backToPickNew() {
        previewJob?.cancel()
        _newSong.value = null
        _query.value = ""
        _preview.value = ReplaceSongPreviewState.Idle
        _step.value = ReplaceSongStep.PickNew
    }

    fun setConflictResolution(resolution: PlaylistConflictResolution) {
        _conflictResolution.value = resolution
    }

    private var previewJob: Job? = null

    private fun beginPreview(old: Song, new: Song) {
        previewJob?.cancel()
        // A conflict choice belongs to exactly one reviewed pair. Never carry it into a refresh or
        // a different replacement whose conflicts may be unrelated (or whose picker may be hidden).
        _conflictResolution.value = PlaylistConflictResolution.KeepOld
        _preview.value = ReplaceSongPreviewState.Loading(old, new)
        previewJob = viewModelScope.launch {
            _preview.value = try {
                when (val result = repository.previewReplaceSong(old.id, new.id)) {
                    is ReplaceSongPreviewResult.Ready -> ReplaceSongPreviewState.Ready(
                        result.preview,
                        result.membershipSnapshot,
                    )

                    ReplaceSongPreviewResult.OldSongMissing -> ReplaceSongPreviewState.Failed(
                        stringResource(R.string.replace_song_failed_old_missing),
                        retryable = false,
                    )

                    ReplaceSongPreviewResult.NewSongMissing -> ReplaceSongPreviewState.Failed(
                        stringResource(R.string.replace_song_failed_new_missing),
                        retryable = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A preview only reads, so nothing can have changed.
                Log.w(TAG, "Replace preview failed", e)
                ReplaceSongPreviewState.Failed(
                    stringResource(R.string.replace_song_failed_generic),
                    retryable = true,
                )
            }
        }
    }

    /** Recomputes the preview after a [ReplaceSongPreviewState.Failed], without leaving the step. */
    fun retryPreview() {
        val old = _oldSong.value ?: return
        val new = _newSong.value ?: return
        beginPreview(old, new)
    }

    /**
     * Confirms the replacement described by [ready]'s own preview and pins its membership
     * snapshot through the repository's transaction-time validation.
     *
     * Does nothing while a replace is running or once one has completed: a second confirm on a
     * finished replace would only take another backup and report the deleted song as missing.
     * After a failure the user may confirm again.
     *
     * Navigation is deliberately not done here: the screen reacts to [outcome] instead, so this
     * holds no callback into the composition it would outlive.
     */
    fun replace(ready: ReplaceSongPreviewState.Ready) {
        val current = _outcome.value
        if (current is ReplaceSongOutcome.InProgress || current is ReplaceSongOutcome.Complete) return
        _outcome.value = ReplaceSongOutcome.InProgress
        viewModelScope.launch {
            _outcome.value = try {
                val result = repository.replaceSong(
                    oldSongId = ready.preview.oldSong.id,
                    newSongId = ready.preview.newSong.id,
                    conflictResolution = _conflictResolution.value,
                    expectedMemberships = ready.membershipSnapshot,
                    onStage = { stage ->
                        if (stage == ReplaceSongStage.MovingReplacement) _movingReplacement.value = true
                    },
                )
                outcomeFor(result, ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Only a failure before the commit reaches here: the repository reports everything
                // after the commit inside its Success result, so "Nothing was changed" holds.
                Log.w(TAG, "Replace failed before it committed", e)
                ReplaceSongOutcome.Failed(
                    stringResource(R.string.replace_song_failed_generic),
                )
            } finally {
                _movingReplacement.value = false
            }
        }
    }

    private fun outcomeFor(result: ReplaceSongResult, ready: ReplaceSongPreviewState.Ready): ReplaceSongOutcome =
        when (result) {
            is ReplaceSongResult.Success -> completedReplaceOutcome(result, ready.preview.oldSong, stringResource)

            ReplaceSongResult.OldSongMissing -> ReplaceSongOutcome.Failed(
                stringResource(R.string.replace_song_failed_old_missing),
            )

            ReplaceSongResult.NewSongMissing -> ReplaceSongOutcome.Failed(
                stringResource(R.string.replace_song_failed_new_missing),
            )

            ReplaceSongResult.PreviewStale -> {
                beginPreview(ready.preview.oldSong, ready.preview.newSong)
                ReplaceSongOutcome.Failed(
                    stringResource(R.string.replace_song_preview_stale),
                )
            }

            is ReplaceSongResult.OldSongBlocked -> ReplaceSongOutcome.Failed(
                appContext.destructiveFileBlockMessage(result.reason),
            )

            is ReplaceSongResult.NewSongUnusable -> ReplaceSongOutcome.Failed(
                stringResource(
                    when (result.reason) {
                        ReplacementFilePreflightResult.SourceGone ->
                            R.string.replace_song_new_file_gone

                        ReplacementFilePreflightResult.IdentityMismatch ->
                            R.string.replace_song_new_file_changed
                    },
                ),
            )

            ReplaceSongResult.NewSongUnreadable -> ReplaceSongOutcome.Failed(
                stringResource(R.string.replace_song_failed_replacement_unreadable),
            )

            ReplaceSongResult.SameSong -> ReplaceSongOutcome.Failed(
                stringResource(R.string.replace_song_failed_same_song),
            )
        }

    /**
     * Retries the old audio file's delete from the completion dialog. Only the file's part of the
     * outcome changes; a failed retry keeps the dialog and its Retry.
     */
    fun retryAudioFileDeletion(target: AudioCleanupRetryTarget) {
        val previous = _outcome.value as? ReplaceSongOutcome.Complete ?: return
        if (_cleanupRetryInProgress.value) return
        _cleanupRetryInProgress.value = true
        viewModelScope.launch {
            _outcome.value = try {
                retriedCleanupOutcome(
                    repository.retryAudioFileDeletion(target.songId, target.filePath),
                    previous,
                    target,
                    stringResource,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Audio cleanup retry failed", e)
                previous.copy(
                    fileWarning = stringResource(R.string.replace_song_done_cleanup_uncertain),
                    retryTarget = target,
                )
            } finally {
                _cleanupRetryInProgress.value = false
            }
        }
    }

    fun dismissOutcome() {
        _outcome.value = null
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(ReplaceSongViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val appContext = context.applicationContext
            // Class<T> is erased; this factory only ever builds ReplaceSongViewModel.
            @Suppress("UNCHECKED_CAST")
            return ReplaceSongViewModel(
                repository = SongRepositoryImpl.create(appContext),
                appContext = appContext,
            ) as T
        }
    }
}
