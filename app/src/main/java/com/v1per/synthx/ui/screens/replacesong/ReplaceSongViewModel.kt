package com.v1per.synthx.ui.screens.replacesong

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.PlaylistConflictResolution
import com.v1per.synthx.data.repository.ReplaceSongPreview
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongDeletionOutcome
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
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
    data class Ready(val preview: ReplaceSongPreview) : ReplaceSongPreviewState()
    data class Failed(val message: String) : ReplaceSongPreviewState()
}

/** Terminal state of the replace itself, once confirmed. */
sealed class ReplaceSongOutcome {
    data object InProgress : ReplaceSongOutcome()

    /**
     * The replacement landed. [warning] is null for the ordinary case, where the screen simply
     * navigates away — the changed library is its own feedback and a "done" dialog on a wizard
     * that is closing itself is noise. It is non-null only when the old song's audio file could
     * not be deleted, which the user has no other way to find out and must acknowledge before
     * the screen closes.
     */
    data class Complete(val warning: String?) : ReplaceSongOutcome()

    data class Failed(val message: String) : ReplaceSongOutcome()
}

/** Matches SearchViewModel's own query debounce — same in-memory filter over the same list. */
private const val QueryDebounceMs = 200L

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
        repository.getSongs(),
        _query.debounce(QueryDebounceMs),
        _oldSong,
        _step,
    ) { songs, query, excluded, step ->
        songs.asSequence()
            .filter { song ->
                // The replacement has to be a file that actually exists, and cannot be the song
                // being replaced. Neither restriction applies when choosing the song to replace:
                // a missing song is a perfectly good thing to want to swap out.
                when (step) {
                    ReplaceSongStep.PickOld -> true
                    else -> !song.isMissing && song.id != excluded?.id
                }
            }
            .filter { song -> song.matches(query) }
            .toPersistentList()
    }
        .flowOn(Dispatchers.Default)
        .catch { emit(persistentListOf()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private fun Song.matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return title.lowercase().contains(needle) || artist.lowercase().contains(needle)
    }

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
        _preview.value = ReplaceSongPreviewState.Loading(old, new)
        previewJob = viewModelScope.launch {
            _preview.value = try {
                repository.previewReplaceSong(old.id, new.id)
                    ?.let { ReplaceSongPreviewState.Ready(it) }
                    ?: ReplaceSongPreviewState.Failed(
                        stringResource(R.string.replace_song_failed_generic),
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ReplaceSongPreviewState.Failed(
                    stringResource(R.string.replace_song_failed_generic),
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
     * Confirms the replacement described by [ready]'s own preview — the preview state is the
     * argument rather than something re-read from [oldSong]/[newSong] here, so what is committed
     * is exactly what the user was shown.
     *
     * Navigation is deliberately not done here: the screen reacts to [outcome] instead, so this
     * holds no callback into the composition it would outlive.
     */
    fun replace(ready: ReplaceSongPreviewState.Ready) {
        if (_outcome.value == ReplaceSongOutcome.InProgress) return
        _outcome.value = ReplaceSongOutcome.InProgress
        viewModelScope.launch {
            _outcome.value = try {
                when (
                    val result = repository.replaceSong(
                        oldSongId = ready.preview.oldSong.id,
                        newSongId = ready.preview.newSong.id,
                        conflictResolution = _conflictResolution.value,
                    )
                ) {
                    is ReplaceSongResult.Success -> ReplaceSongOutcome.Complete(
                        warning = when (result.oldAudioCleanup) {
                            SongDeletionOutcome.Deleted -> null
                            SongDeletionOutcome.FileRemained ->
                                stringResource(R.string.replace_song_done_file_remained)
                            SongDeletionOutcome.CleanupUncertain ->
                                stringResource(R.string.replace_song_done_cleanup_uncertain)
                        },
                    )
                    ReplaceSongResult.OldSongMissing -> ReplaceSongOutcome.Failed(
                        stringResource(R.string.replace_song_failed_old_missing),
                    )
                    ReplaceSongResult.NewSongMissing -> ReplaceSongOutcome.Failed(
                        stringResource(R.string.replace_song_failed_new_missing),
                    )
                    ReplaceSongResult.SameSong -> ReplaceSongOutcome.Failed(
                        stringResource(R.string.replace_song_failed_same_song),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ReplaceSongOutcome.Failed(
                    stringResource(R.string.replace_song_failed_generic),
                )
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
            @Suppress("UNCHECKED_CAST")
            return ReplaceSongViewModel(
                repository = SongRepositoryImpl.create(appContext),
                appContext = appContext,
            ) as T
        }
    }
}
