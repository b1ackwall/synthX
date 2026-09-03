package com.v1per.synthx.ui.screens.issues

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.backup.BackupAttemptOutcome
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.ExportAttemptOutcome
import com.v1per.synthx.data.backup.VerificationAttemptOutcome
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Intermediate holder for the 5-source combine() leg — see [IssuesViewModel.init]. */
private data class PartialSources(
    val missingSongs: List<MissingSongInfo>,
    val backupOutcome: BackupAttemptOutcome?,
    val exportOutcome: ExportAttemptOutcome?,
    val verificationOutcome: VerificationAttemptOutcome?,
    val destructiveCount: Int,
)

class IssuesViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<Issue>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Issue>>> = _uiState.asStateFlow()

    /** Gates the delete row in the menu below — see [DeleteSongsGate]. */
    val deleteSongsEnabled: StateFlow<Boolean> = DeleteSongsGate.deleteSongsEnabled

    // Filesystem-backed, not DB-backed (see ArtRecovery), so unlike every other source above it
    // has nothing Room can invalidate a Flow on — it's a plain one-shot check, re-run on this
    // ViewModel's own init and again after fixMissingArt(), same "fresh ViewModel per visit,
    // re-check on open" shape BackupsAndRestoreViewModel already uses for folder health.
    private val _missingArtSongIds = MutableStateFlow<List<String>>(emptyList())

    init {
        refreshMissingArtSongIds()
        viewModelScope.launch {
            // combine() only has overloads up to 5 flows of a shared type; these six carry
            // different types, so the 6th (grantLost) is folded in via a second combine() leg
            // rather than forcing everything into one call. missingArtSongIds is folded in via
            // a third leg for the same reason.
            combine(
                repository.getMissingSongs(),
                BackupOutcomeStore.lastBackupOutcome,
                BackupOutcomeStore.lastExportOutcome,
                BackupOutcomeStore.lastVerificationOutcome,
                DestructiveActionTracker.count,
            ) { missingSongs, backupOutcome, exportOutcome, verificationOutcome, destructiveCount ->
                PartialSources(missingSongs, backupOutcome, exportOutcome, verificationOutcome, destructiveCount)
            }.combine(BackupFolderLaunchCheck.grantLost) { partial, grantLost ->
                partial to grantLost
            }.combine(_missingArtSongIds) { (partial, grantLost), missingArtSongIds ->
                buildIssues(partial, grantLost, missingArtSongIds)
            }.catch { e ->
                _uiState.value = UiState.Error(e.message ?: "Failed to load issues")
            }.collect { issues ->
                _uiState.value = if (issues.isEmpty()) UiState.Empty else UiState.Success(data = issues)
            }
        }
    }

    private fun refreshMissingArtSongIds() {
        viewModelScope.launch {
            _missingArtSongIds.value = runCatching { repository.findMissingArtSongIds() }.getOrDefault(emptyList())
        }
    }

    /**
     * Safety-net issues (backup/export/verification health, grant-lost, destructive-action
     * count) always sort above missing songs, regardless of timestamp: that risk is ongoing and
     * compounding, while a missing song is already-bounded damage. Within the safety-net group,
     * grant-lost sorts first (it blocks every future backup outright — the most severe signal),
     * the three timestamped outcomes sort by recency in the middle, and the destructive-action
     * count sorts last (the softest signal — a plain count with no failure behind it). Missing
     * songs keep their existing most-recent-first order.
     */
    private fun buildIssues(
        partial: PartialSources,
        grantLost: Boolean,
        missingArtSongIds: List<String>,
    ): List<Issue> {
        val timestampedIssues = listOfNotNull(
            partial.backupOutcome
                ?.takeIf { !it.succeeded }
                ?.let { it.timestamp to Issue.BackupUnhealthy(it) },
            partial.exportOutcome
                ?.takeIf { !it.succeeded }
                ?.let { it.timestamp to Issue.ExportUnhealthy(it) },
            partial.verificationOutcome
                ?.takeIf { !it.succeeded }
                ?.let { it.timestamp to Issue.VerificationFailed(it) },
        ).sortedByDescending { it.first }.map { it.second }

        val safetyNetIssues = buildList {
            if (grantLost) add(Issue.BackupFolderGrantLost)
            addAll(timestampedIssues)
            if (partial.destructiveCount > 0) {
                add(Issue.DestructiveActionsSinceBackup(partial.destructiveCount))
            }
            if (missingArtSongIds.isNotEmpty()) {
                add(Issue.MissingArt(missingArtSongIds))
            }
        }

        val missingSongIssues = partial.missingSongs
            .sortedByDescending { it.missingSince }
            .map { Issue.MissingSong(it) }

        return safetyNetIssues + missingSongIssues
    }

    // The list is Flow-backed, so Room's invalidation drops the row from the UI on its own.
    fun deleteSong(id: String) {
        viewModelScope.launch {
            repository.deleteSong(id)
        }
    }

    /** Re-extracts art for [songIds] (the affected songs from one [Issue.MissingArt] entry). */
    fun fixMissingArt(songIds: List<String>) {
        viewModelScope.launch {
            repository.recoverMissingArt(songIds)
            refreshMissingArtSongIds()
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(IssuesViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return IssuesViewModel(SongRepositoryImpl.create(context)) as T
        }
    }
}
