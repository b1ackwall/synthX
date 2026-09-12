package com.v1per.synthx.ui.screens.issues

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.v1per.synthx.data.backup.BackupAttemptOutcome
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.ExportAttemptOutcome
import com.v1per.synthx.data.backup.VerificationAttemptOutcome
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.data.repository.SongDeletionOutcome
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.IssuesScanSnapshot
import com.v1per.synthx.data.scanner.IssuesScanWorker
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.common.launchCatching
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SynthXIssues"

/** Intermediate holder for the 5-source combine() leg — see [IssuesViewModel.init]. */
private data class PartialSources(
    val missingSongs: List<MissingSongInfo>,
    val backupOutcome: BackupAttemptOutcome?,
    val exportOutcome: ExportAttemptOutcome?,
    val verificationOutcome: VerificationAttemptOutcome?,
    val destructiveCount: Int,
)

/**
 * What the Issues screen's fixed status area renders. Three states, not two, because "we don't
 * know yet whether a scan is running" is genuinely distinct from both: resolving WorkManager's
 * WorkInfo is asynchronous, and showing a last-scan summary during that window would be exactly
 * the stale-summary lie this whole rework exists to remove — briefly, but still a lie.
 */
sealed interface IssuesScanStatus {
    /** WorkManager hasn't reported yet. Sub-frame in practice; the button stays disabled. */
    data object Resolving : IssuesScanStatus

    /** A scan is in flight, whichever of the three triggers started it. */
    data object Scanning : IssuesScanStatus

    /** No scan running. [lastScanTimestamp] of 0 means none has ever completed. */
    data class Idle(val lastScanTimestamp: Long, val issueCount: Int) : IssuesScanStatus
}

sealed class IssuesActionEvent {
    data object ActionFailed : IssuesActionEvent()
    data object DeletedButFileRemained : IssuesActionEvent()
    data object DeletedButCleanupUncertain : IssuesActionEvent()
}

/**
 * Reads the Issues screen's eight categories from live signals and the worker cache.
 *
 * The six cheap ones stay live: five are process-wide in-memory StateFlows and the sixth is a
 * Room Flow, so they are always current and cost nothing to re-read on every visit. The
 * artwork aggregates are read from a shared snapshot. [IssuesScanWorker] computes missing art
 * and missing palette data (daily, or on demand) and caches them in
 * [PreferencesManager]; this ViewModel reads that cache.
 *
 * That split is what fixes the bug this replaced: the old init block kicked off the expensive
 * sweep on every navigation to the screen and seeded its result with an empty list, so for the
 * seconds-to-a-minute it ran the screen confidently reported "No issues found" — indistinguishable
 * from a genuine all-clear — and then silently corrected itself.
 */
class IssuesViewModel(
    private val context: Context,
    private val repository: SongRepository,
    private val preferences: PreferencesManager,
    private val enqueueScan: (Context) -> Unit = { scanContext -> IssuesScanWorker.enqueue(scanContext) },
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    private val _uiState = MutableStateFlow<UiState<List<Issue>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Issue>>> = _uiState.asStateFlow()

    /** Gates the delete row in the menu below — see [DeleteSongsGate]. */
    val deleteSongsEnabled: StateFlow<Boolean> = DeleteSongsGate.deleteSongsEnabled

    private val _events = MutableSharedFlow<IssuesActionEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<IssuesActionEvent> = _events.asSharedFlow()

    /**
     * Covers the gap between "the user asked for a scan" and "WorkManager reports one running":
     * enqueueing is asynchronous, so without this the list would flash its stale rows back for a
     * frame or two after the Scan button is tapped. Also held for the whole of a Fix, which
     * mutates art on disk and is therefore just as much "results are not to be trusted right
     * now" as the rescan that follows it. Handed off (and bounded) by [startScan].
     */
    private val _pendingScan = MutableStateFlow(false)

    /**
     * Null until WorkManager first reports — see [IssuesScanStatus.Resolving]. Reads the same
     * unique work name every trigger enqueues under, which is what makes the status honest for
     * a scan this screen did not start (the daily automatic one, or one begun on an earlier
     * visit): the state lives in WorkManager, not in this ViewModel, so it survives the
     * ViewModel being destroyed and rebuilt by navigation.
     *
     * Same technique LibraryManagementViewModel already uses for its scan status row.
     */
    private val scanRunning: StateFlow<Boolean?> = workManager
        .getWorkInfosForUniqueWorkFlow(IssuesScanWorker.UNIQUE_WORK_NAME)
        .map<List<WorkInfo>, Boolean?> { infos -> infos.any { !it.state.isFinished } }
        .catch { e ->
            Log.w(TAG, "Issues scan status unavailable — treating as idle", e)
            emit(false)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val scanStatus: StateFlow<IssuesScanStatus> =
        combine(scanRunning, _pendingScan) { running, pending ->
            when {
                running == null -> IssuesScanStatus.Resolving
                running || pending -> IssuesScanStatus.Scanning
                // Read at emission time rather than captured once, so the emission that follows
                // a scan completing already carries that scan's freshly written summary.
                else -> IssuesScanStatus.Idle(
                    lastScanTimestamp = preferences.lastIssuesScanTimestamp,
                    issueCount = preferences.lastIssuesScanIssueCount,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, IssuesScanStatus.Resolving)

    init {
        viewModelScope.launch {
            // combine() only has overloads up to 5 flows of a shared type; these six carry
            // different types, so the 6th (grantLost) is folded in via a second combine() leg
            // rather than forcing everything into one call. scanStatus is folded in via a
            // third leg for the same reason.
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
            }.combine(scanStatus) { (partial, grantLost), status ->
                // Null means "nothing to show yet", NOT "nothing found" — while a scan is in
                // flight the list is held completely empty rather than partially revealed or
                // left showing rows the running scan may be about to contradict.
                if (status !is IssuesScanStatus.Idle) {
                    null
                } else {
                    buildIssues(
                        partial,
                        grantLost,
                        IssuesScanSnapshot.stableOrder(preferences.missingArtSongIds),
                        IssuesScanSnapshot.stableOrder(preferences.missingPaletteSongIds),
                    )
                }
            }.catch { e ->
                _uiState.value = UiState.Error(e.message ?: "Failed to load issues")
            }.collect { issues ->
                _uiState.value = when {
                    issues == null -> UiState.Loading
                    issues.isEmpty() -> UiState.Empty
                    else -> UiState.Success(data = issues)
                }
            }
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
     *
     * The two artwork aggregates arrive from the worker cache and stay adjacent.
     * IssuesScanSnapshot.issueCount mirrors this shape for the status line's count.
     */
    private fun buildIssues(
        partial: PartialSources,
        grantLost: Boolean,
        missingArtSongIds: List<String>,
        missingPaletteSongIds: List<String>,
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
            if (missingPaletteSongIds.isNotEmpty()) {
                add(Issue.MissingPalette(missingPaletteSongIds))
            }
        }

        val missingSongIssues = partial.missingSongs
            .sortedByDescending { it.missingSince }
            .map { Issue.MissingSong(it) }

        return safetyNetIssues + missingSongIssues
    }

    // The list is Flow-backed, so Room's invalidation drops the row from the UI on its own.
    fun deleteSong(id: String) {
        launchCatching(onError = { _events.tryEmit(IssuesActionEvent.ActionFailed) }) {
            when (repository.deleteSong(id)) {
                SongDeletionOutcome.Deleted -> Unit
                SongDeletionOutcome.FileRemained ->
                    _events.tryEmit(IssuesActionEvent.DeletedButFileRemained)
                SongDeletionOutcome.CleanupUncertain ->
                    _events.tryEmit(IssuesActionEvent.DeletedButCleanupUncertain)
            }
        }
    }

    /** The Scan button. Same work, same queue, same policy as the daily automatic trigger. */
    fun startScan() {
        _pendingScan.value = true
        enqueueScan(context)
        viewModelScope.launch {
            // Hand off to the real WorkInfo signal the moment it appears, so the two never
            // both go false at once and flash the old list back. The timeout is a safety net
            // for the case where the run finishes between two emissions and no active state is
            // ever observed — without it, a stuck flag would disable the button indefinitely.
            withTimeoutOrNull(PendingScanHandoffMs) { scanRunning.first { it == true } }
            _pendingScan.value = false
        }
    }

    /**
     * Re-extracts art for [songIds] (the affected songs from one [Issue.MissingArt] entry), then
     * triggers a full rescan rather than re-checking missing art alone: the cache the screen
     * reads is written by [IssuesScanWorker] and nothing else, so letting the fix update it
     * directly would give the screen a second, divergent source of truth. The scan flag is held
     * across the recovery itself, not just the rescan — art is being rewritten on disk, so the
     * cached rows are no more trustworthy during the fix than during the scan that follows it.
     */
    fun fixMissingArt(songIds: List<String>) {
        _pendingScan.value = true
        viewModelScope.launch {
            runCatching { repository.recoverMissingArt(songIds) }
                .onFailure { e ->
                    Log.w(TAG, "Art recovery failed for ${songIds.size} song(s)", e)
                    _events.tryEmit(IssuesActionEvent.ActionFailed)
                }
            // Runs on both outcomes: a partial or failed recovery still needs the cache
            // reconciled with whatever is actually on disk now.
            startScan()
        }
    }

    /** Keep the scan status active throughout the fix and refresh the shared cache afterward. */
    fun fixMissingPalette(songIds: List<String>) {
        _pendingScan.value = true
        viewModelScope.launch {
            runCatching { repository.recoverPaletteData(songIds) }
                .onFailure { e ->
                    Log.w(TAG, "Palette recovery failed for ${songIds.size} song(s)", e)
                    _events.tryEmit(IssuesActionEvent.ActionFailed)
                }
            startScan()
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(IssuesViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return IssuesViewModel(
                context = appContext,
                repository = SongRepositoryImpl.create(appContext),
                preferences = PreferencesManager(appContext),
            ) as T
        }
    }

    private companion object {
        /** Upper bound on how long [_pendingScan] can hold the UI before WorkInfo takes over. */
        const val PendingScanHandoffMs = 5_000L
    }
}
