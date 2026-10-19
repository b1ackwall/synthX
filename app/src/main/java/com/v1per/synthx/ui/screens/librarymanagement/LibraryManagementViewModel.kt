package com.v1per.synthx.ui.screens.librarymanagement

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.data.scanner.scanDegradations
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.common.LIBRARY_SCAN_TAG
import com.v1per.synthx.ui.common.NEW_ARRIVALS_TAG
import com.v1per.synthx.ui.common.ScanStatusStrings
import com.v1per.synthx.ui.common.VERIFY_TAG
import com.v1per.synthx.ui.common.scanStatusFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * WorkManager exposes no public API to prune a single unique work item's terminal
 * WorkInfo — only the database-wide [androidx.work.WorkManager.pruneWork] used at cold
 * start in MainActivity, which is too broad to call on every screen exit (it would also
 * clear other screens' finished work, e.g. a still-undismissed Restore failure). This
 * in-memory, process-lifetime flag substitutes for that: it's set to the id of the most
 * recent *finished* [LibraryScanWorker] run when [LibraryManagementViewModel] is cleared,
 * and consulted by the next instance to suppress re-showing that same run's terminal
 * status text. An in-progress run is never recorded here, so it always survives navigating
 * away and back. Cleared implicitly by process death, same as the cold-start prune.
 *
 * Now shared across all three scan workers (not just [LibraryScanWorker]) since [workInfo]
 * below resolves to whichever of the three most recently ran — a single id is still enough,
 * since only one of them can ever be the "current" one at a time.
 */
private object ScanStatusAcknowledgement {
    var acknowledgedWorkId: UUID? = null
}

/** How long a terminal status message (e.g. "No folder selected") stays
 *  visible before auto-clearing, matching typical snackbar/status-message durations. */
private const val StatusMessageDisplayMs = 4_000L

/** How long an unresolvable first-scan id shows as Running before it is treated as lost. */
private const val MissingFirstScanGraceMs = 10_000L

class LibraryManagementViewModel(
    // Only applicationContext is stored here (see Factory).
    @field:SuppressLint("StaticFieldLeak")
    internal val context: Context,
    internal val preferencesManager: PreferencesManager,
    private val repository: SongRepository,
    internal val backupManager: BackupManager,
    internal val releasePersistableGrant: (Uri) -> Unit = { uri ->
        context.contentResolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    },
) : ViewModel() {

    internal enum class ConfiguredFolderRole { MUSIC, NEW_ARRIVALS, BACKUP }

    internal val workManager = WorkManager.getInstance(context)

    private val strings = ScanStatusStrings { id, args -> context.getString(id, *args) }

    /** Drives the Issues row badge. Counted in SQL rather than by collecting the list. */
    val missingSongCount: StateFlow<Int> = repository.getMissingSongCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    internal val _musicFolderUri = MutableStateFlow(
        preferencesManager.musicFolderUri?.let { it.toUri() },
    )
    val musicFolderUri: StateFlow<Uri?> = _musicFolderUri.asStateFlow()

    internal val _newArrivalsFolderUri = MutableStateFlow(
        preferencesManager.newArrivalsFolderUri?.let { it.toUri() },
    )
    val newArrivalsFolderUri: StateFlow<Uri?> = _newArrivalsFolderUri.asStateFlow()

    internal val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()

    /** Separate from [folderError] so a rejected pick's message renders under the row the
     *  user actually tapped rather than under the music folder row above it. */
    internal val _newArrivalsFolderError = MutableStateFlow<String?>(null)
    val newArrivalsFolderError: StateFlow<String?> = _newArrivalsFolderError.asStateFlow()

    // The one New Arrivals scan whose start was deferred behind a scan already running,
    // tracked by request id — the same pattern as the first scan — rather than by the shared
    // unique name, which any of the three scan workers can also occupy.
    internal val _deferredNewArrivalsScanId = MutableStateFlow<UUID?>(null)

    /**
     * Informational, not an error: "a scan is already running, this folder will follow". Its own
     * channel so it renders neutrally instead of as a red error, and so it is cleared by what it
     * describes — its scan leaving the waiting states — rather than by a timer or by the next
     * unrelated pick. Null whenever nothing is deferred.
     *
     * A null WorkInfo is normally the short enqueue-to-publication gap, so it still shows the
     * notice, but only for [MissingFirstScanGraceMs]: a record that never appears must not leave a
     * stale promise up forever.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val newArrivalsFolderNotice: StateFlow<String?> = _deferredNewArrivalsScanId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<String?>(null)
            } else {
                workManager.getWorkInfoByIdFlow(id).transformLatest<WorkInfo?, String?> { info ->
                    val notice = context.getString(R.string.status_new_arrivals_scan_deferred)
                    when {
                        info == null -> {
                            emit(notice)
                            delay(MissingFirstScanGraceMs)
                            emit(null)
                        }

                        isDeferredScanPending(info.state) -> emit(notice)

                        else -> emit(null)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Thin passthrough to the shared in-memory gate — see [DeleteSongsGate]. */
    val deleteSongsEnabled: StateFlow<Boolean> = DeleteSongsGate.deleteSongsEnabled

    // Set only for states WorkManager can't express — currently just "no folder picked",
    // which never reaches the worker. Cleared as soon as a scan is actually enqueued.
    internal val _localStatus = MutableStateFlow<String?>(null)

    // The single source of truth for scan state. Reading it from WorkManager rather than
    // from local flows is what makes progress survive navigation and app restart: a
    // freshly constructed ViewModel observing a scan already in flight sees its real
    // state, not a default.
    //
    // The unique name is shared by three workers: LibraryScanWorker (the manual rescan this
    // row historically reported on), VerifyLibraryWorker (a silent launch-time sweep) and
    // NewArrivalsScanWorker (a silent launch-time worker, chained after Verify). All three are
    // now surfaced here — see selectRelevantWorkInfo — rather than filtering down to just
    // LibraryScanWorker's tag, so the status row reflects whichever of the three is actually
    // running or most recently finished.
    private val workInfo: StateFlow<WorkInfo?> = workManager
        .getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
        .map { infos -> selectRelevantWorkInfo(infos) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isScanning: StateFlow<Boolean> = workInfo
        .map { info -> info != null && !info.state.isFinished }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Determinate progress only ever comes from LibraryScanWorker — it's the only one of the
    // three that calls setProgress/setForeground (NewArrivalsScanWorker and VerifyLibraryWorker
    // are both silent by design). Null here for the other two just means ScanSection renders
    // its existing indeterminate bar for them, which is the correct representation.
    val scanProgress: StateFlow<ScanProgress?> = workInfo
        .map { info ->
            if (info == null || info.state != WorkInfo.State.RUNNING) return@map null
            if (LIBRARY_SCAN_TAG !in info.tags) return@map null
            ScanProgress(
                current = info.progress.getInt(LibraryScanWorker.KEY_CURRENT, -1),
                total = info.progress.getInt(LibraryScanWorker.KEY_TOTAL, -1),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val rescanStatus: StateFlow<String?> = combine(workInfo, _localStatus) { info, local ->
        val alreadyAcknowledged = info != null &&
            info.state.isFinished &&
            info.id == ScanStatusAcknowledgement.acknowledgedWorkId
        local ?: if (alreadyAcknowledged) null else statusFor(info)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scanStatusNeedsAttention: StateFlow<Boolean> = workInfo
        .map { info ->
            info?.state == WorkInfo.State.FAILED ||
                (
                    info?.state == WorkInfo.State.SUCCEEDED &&
                        info.outputData.scanDegradations().isNotEmpty()
                    )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Either scan worker clears its own stored URI when it finds the grant revoked.
        // Mirror that back into the UI so the folder row stops showing a path the app can't
        // read. Watched per-stage, since the two workers share one unique work name.
        viewModelScope.launch {
            workInfo.collect { info ->
                // workInfo can now resolve to any of the three tagged runs — only react to a
                // LibraryScanWorker failure here; a NewArrivals/Verify failure has nothing to
                // do with the main music-folder grant.
                if (info?.state == WorkInfo.State.FAILED && LIBRARY_SCAN_TAG in info.tags) {
                    _musicFolderUri.value = preferencesManager.musicFolderUri?.let { it.toUri() }
                }
            }
        }
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
                .collect { infos ->
                    val failed = infos.any {
                        NEW_ARRIVALS_TAG in it.tags && it.state == WorkInfo.State.FAILED
                    }
                    if (failed) {
                        _newArrivalsFolderUri.value =
                            preferencesManager.newArrivalsFolderUri?.let { it.toUri() }
                    }
                }
        }
    }

    /**
     * Resolves the shared unique-work queue's entries down to the one WorkInfo worth showing:
     * whichever tagged run is currently active, or — if none is — the most recently finished
     * one, so a completed result summary keeps lingering after the run the same way it always
     * has for LibraryScanWorker alone. WorkManager gives no completion timestamp on WorkInfo,
     * but [getWorkInfosForUniqueWorkFlow] returns entries in roughly insertion order, so the
     * last finished entry in the filtered list is the most recent one.
     */
    private fun selectRelevantWorkInfo(infos: List<WorkInfo>): WorkInfo? {
        val tagged = infos.filter { info ->
            LIBRARY_SCAN_TAG in info.tags || NEW_ARRIVALS_TAG in info.tags || VERIFY_TAG in info.tags
        }
        tagged.firstOrNull { !it.state.isFinished }?.let { return it }
        return tagged.lastOrNull { it.state.isFinished }
    }

    private fun statusFor(info: WorkInfo?): String? = scanStatusFor(info, strings)

    /** Sets a terminal (non-"in progress") status message, then clears it after
     *  [StatusMessageDisplayMs] — guarded so a newer status set in the meantime isn't
     *  clobbered by a stale clear from an earlier call. */
    internal fun MutableStateFlow<String?>.showTemporarily(message: String?) {
        value = message
        viewModelScope.launch {
            delay(StatusMessageDisplayMs)
            if (value == message) value = null
        }
    }

    fun setFolderError(message: String) {
        _folderError.value = message
    }

    fun clearFolderError() {
        _folderError.value = null
    }

    fun setNewArrivalsFolderError(message: String) {
        _newArrivalsFolderError.value = message
    }

    fun saveMusicFolder(uri: Uri) = saveMusicFolderInternal(uri)

    /**
     * Fresh-install onboarding's folder pick. Persist only — the same shape as
     * [saveRecoveryMusicFolder], and for the same reason: nothing has been confirmed yet, so there
     * is nothing to back up, no scan to start and no completion to record. All of that happens in
     * [startFirstScan], fired from Done. Library Management's re-pick goes through
     * [saveMusicFolder], with its backup-first + REPLACE behaviour.
     */
    fun saveOnboardingMusicFolder(uri: Uri) = saveRecoveryMusicFolder(uri)

    // The one scan the wizard's ScanningStep is watching, tracked by request id rather than by the
    // shared unique name (which any of the three scan workers can also occupy). Seeded from
    // preferences so a process-death resume re-attaches to the very same scan.
    internal val _firstScanRequestId = MutableStateFlow(
        preferencesManager.firstScanRequestId?.let { value ->
            runCatching { UUID.fromString(value) }.getOrNull()
        },
    )

    /**
     * What ScanningStep should be showing, derived from the tracked request's own WorkInfo:
     * [FirstScanState.Running] until it is terminal, then [FirstScanState.Succeeded] or
     * [FirstScanState.Failed]. FAILED and CANCELLED are *not* success.
     *
     * A null WorkInfo is ambiguous: normally the short enqueue-to-publication gap, but it is also
     * what an id whose record no longer exists looks like. It is shown as Running for
     * [MissingFirstScanGraceMs], then as Failed, so the wizard can never spin forever on a scan
     * that WorkManager has no record of.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val firstScanState: StateFlow<FirstScanState> = _firstScanRequestId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<FirstScanState>(FirstScanState.Failed(lostFirstScanMessage(), cancelled = false))
            } else {
                workManager.getWorkInfoByIdFlow(id).transformLatest<WorkInfo?, FirstScanState> { info ->
                    if (info == null) {
                        emit(FirstScanState.Running(null))
                        delay(MissingFirstScanGraceMs)
                        emit(FirstScanState.Failed(lostFirstScanMessage(), cancelled = false))
                    } else {
                        emit(firstScanStateFor(info, strings))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirstScanState.Running(null))

    private fun lostFirstScanMessage(): String = strings.get(
        R.string.scan_status_failed,
        strings.get(R.string.onboarding_scan_status_unavailable),
    )

    /**
     * The single "start first scan" call, fired from Done and by Retry. Marks
     * onboarding complete, enqueues with REPLACE (any earlier scan is walking a folder that may
     * since have been changed) and durably records the request id — in that order, so a persisted
     * id always names a request that was actually enqueued. Returns the id, or null when there is
     * no persisted folder or the completion/id commit failed (an error is then shown under the
     * folder row).
     *
     * Deliberately synchronous: the caller advances the wizard on the return value, and
     * WorkManager assigns the request id client-side, so nothing here needs to suspend.
     */
    fun startFirstScan(): UUID? = startFirstScanInternal()

    /** Retry after a failed/cancelled first scan — same folder, REPLACE, fresh tracked id. */
    fun retryFirstScan(): UUID? = startFirstScan()

    /** The scan succeeded and the wizard is handing over to Home. */
    fun completeFirstScan() = completeFirstScanInternal()

    /**
     * "Change folder" after a failed/cancelled first scan. Drops the tracked id and un-completes
     * onboarding, so the wizard is once again the honest cold-start destination.
     */
    fun abandonFirstScan() = abandonFirstScanInternal()

    /** Recovery setup only: validate and persist without backing up or scanning the empty library. */
    fun saveRecoveryMusicFolder(uri: Uri) = saveRecoveryMusicFolderInternal(uri)

    /** The optional recovery root also waits until after restore for its first scan. */
    fun saveRecoveryNewArrivalsFolder(uri: Uri) = saveRecoveryNewArrivalsFolderInternal(uri)

    /**
     * Deliberately diverges from [saveMusicFolder]: no [BackupReason.FOLDER_CHANGE] snapshot
     * (nothing about the existing library is invalidated by pointing at a different arrivals
     * tree) and, critically, no [ExistingWorkPolicy.REPLACE] — the shared unique work name may
     * be carrying an in-flight main-root scan, which must not be cancelled by this. Enqueued
     * with [ExistingWorkPolicy.APPEND_OR_REPLACE] (see [NewArrivalsScanWorker.enqueue]'s kdoc)
     * so the freshly-picked folder still gets scanned promptly even if something else is
     * currently running under the shared unique name, rather than the request being silently
     * discarded. When that's the case, the user is told
     * their scan is queued rather than left to wonder why nothing happened — via
     * [newArrivalsFolderNotice], not the error channel.
     */
    fun saveNewArrivalsFolder(uri: Uri) = saveNewArrivalsFolderInternal(uri)

    fun rescanLibrary() {
        if (_musicFolderUri.value == null) {
            _localStatus.showTemporarily(context.getString(R.string.settings_no_folder_selected))
            return
        }
        _localStatus.value = null
        // KEEP — tapping rescan while a scan runs is a no-op rather than a duplicate.
        LibraryScanWorker.enqueue(context, ExistingWorkPolicy.KEEP)
    }

    fun cancelScan() {
        LibraryScanWorker.cancel(context)
    }

    fun setDeleteSongsEnabled(enabled: Boolean) {
        DeleteSongsGate.setDeleteSongsEnabled(enabled)
    }

    // Only acknowledges a *finished* run — an in-progress scan's WorkInfo is never
    // finished, so leaving mid-scan and coming back always shows its real, live state.
    override fun onCleared() {
        super.onCleared()
        workInfo.value?.let { info ->
            if (info.state.isFinished) {
                ScanStatusAcknowledgement.acknowledgedWorkId = info.id
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(LibraryManagementViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return LibraryManagementViewModel(
                context = appContext,
                preferencesManager = PreferencesManager(appContext),
                repository = SongRepositoryImpl.create(appContext),
                backupManager = BackupManager.create(appContext),
            ) as T
        }
    }
}
