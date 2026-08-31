package com.v1per.synthx.ui.screens.librarymanagement

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.data.scanner.RootNesting
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.common.launchCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
 */
private object ScanStatusAcknowledgement {
    var acknowledgedWorkId: UUID? = null
}

/** How long a terminal status message (e.g. "No folder selected") stays
 *  visible before auto-clearing, matching typical snackbar/status-message durations. */
private const val StatusMessageDisplayMs = 4_000L

/** WorkManager tags every request with its worker's fully-qualified class name. */
private val NEW_ARRIVALS_TAG: String = NewArrivalsScanWorker::class.java.name
private val LIBRARY_SCAN_TAG: String = LibraryScanWorker::class.java.name

/** Determinate when [total] is positive; otherwise the scan is in an uncounted phase. */
data class ScanProgress(val current: Int, val total: Int) {
    val isDeterminate: Boolean get() = total > 0
    val fraction: Float get() = if (isDeterminate) current.toFloat() / total else 0f
}

class LibraryManagementViewModel(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val repository: SongRepository,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    /** Drives the Issues row badge. Counted in SQL rather than by collecting the list. */
    val missingSongCount: StateFlow<Int> = repository.getMissingSongCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _musicFolderUri = MutableStateFlow(
        preferencesManager.musicFolderUri?.let { Uri.parse(it) }
    )
    val musicFolderUri: StateFlow<Uri?> = _musicFolderUri.asStateFlow()

    private val _newArrivalsFolderUri = MutableStateFlow(
        preferencesManager.newArrivalsFolderUri?.let { Uri.parse(it) }
    )
    val newArrivalsFolderUri: StateFlow<Uri?> = _newArrivalsFolderUri.asStateFlow()

    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()

    /** Separate from [folderError] so a rejected pick's message renders under the row the
     *  user actually tapped rather than under the music folder row above it. */
    private val _newArrivalsFolderError = MutableStateFlow<String?>(null)
    val newArrivalsFolderError: StateFlow<String?> = _newArrivalsFolderError.asStateFlow()

    /** Thin passthrough to the shared in-memory gate — see [DeleteSongsGate]. */
    val deleteSongsEnabled: StateFlow<Boolean> = DeleteSongsGate.deleteSongsEnabled

    // Set only for states WorkManager can't express — currently just "no folder picked",
    // which never reaches the worker. Cleared as soon as a scan is actually enqueued.
    private val _localStatus = MutableStateFlow<String?>(null)

    // The single source of truth for scan state. Reading it from WorkManager rather than
    // from local flows is what makes progress survive navigation and app restart: a
    // freshly constructed ViewModel observing a scan already in flight sees its real
    // state, not a default.
    //
    // The unique name is shared by three workers: LibraryScanWorker (the manual rescan this
    // row reports on), VerifyLibraryWorker (a silent launch-time sweep with no intended UI),
    // and NewArrivalsScanWorker (a silent launch-time worker, chained after Verify). Excluding
    // only the new-arrivals tag isn't enough — it doesn't distinguish Verify's WorkInfo from
    // LibraryScanWorker's, so an unpruned/concurrent Verify entry could get picked instead of
    // the actual rescan. Matching on LibraryScanWorker's own tag is unambiguous regardless of
    // how many other stages' WorkInfo happen to still be sitting under this unique name.
    private val workInfo: StateFlow<WorkInfo?> = workManager
        .getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
        .map { infos -> infos.firstOrNull { LIBRARY_SCAN_TAG in it.tags } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isScanning: StateFlow<Boolean> = workInfo
        .map { info -> info != null && !info.state.isFinished }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val scanProgress: StateFlow<ScanProgress?> = workInfo
        .map { info ->
            if (info == null || info.state != WorkInfo.State.RUNNING) return@map null
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

    init {
        // Either scan worker clears its own stored URI when it finds the grant revoked.
        // Mirror that back into the UI so the folder row stops showing a path the app can't
        // read. Watched per-stage, since the two workers share one unique work name.
        viewModelScope.launch {
            workInfo.collect { info ->
                if (info?.state == WorkInfo.State.FAILED) {
                    _musicFolderUri.value = preferencesManager.musicFolderUri?.let { Uri.parse(it) }
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
                            preferencesManager.newArrivalsFolderUri?.let { Uri.parse(it) }
                    }
                }
        }
    }

    private fun statusFor(info: WorkInfo?): String? = when (info?.state) {
        null -> null
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            context.getString(R.string.scan_progress_preparing)
        WorkInfo.State.RUNNING -> {
            val current = info.progress.getInt(LibraryScanWorker.KEY_CURRENT, -1)
            val total = info.progress.getInt(LibraryScanWorker.KEY_TOTAL, -1)
            if (total > 0) {
                context.getString(R.string.scan_progress_counted, current, total)
            } else {
                context.getString(R.string.scan_progress_preparing)
            }
        }
        WorkInfo.State.SUCCEEDED -> {
            val data = info.outputData
            val doneMessage = context.getString(
                R.string.scan_status_done,
                data.getInt(LibraryScanWorker.KEY_ADDED, 0),
                data.getInt(LibraryScanWorker.KEY_UPDATED, 0),
                data.getInt(LibraryScanWorker.KEY_MISSING, 0),
            )
            if (data.getBoolean(LibraryScanWorker.KEY_HIGH_IMPACT, false)) {
                val highImpactMessage = context.getString(
                    R.string.scan_status_high_impact,
                    data.getInt(LibraryScanWorker.KEY_IMPACT_COUNT, 0),
                    data.getString(LibraryScanWorker.KEY_BACKUP_FILENAME)
                        ?: context.getString(R.string.scan_status_backup_filename_unknown),
                )
                "$doneMessage $highImpactMessage"
            } else {
                doneMessage
            }
        }
        WorkInfo.State.FAILED -> context.getString(
            R.string.scan_status_failed,
            info.outputData.getString(LibraryScanWorker.KEY_ERROR)
                ?: context.getString(R.string.scan_status_unknown_error),
        )
        WorkInfo.State.CANCELLED -> context.getString(R.string.scan_cancelled)
    }

    /** Sets a terminal (non-"in progress") status message, then clears it after
     *  [StatusMessageDisplayMs] — guarded so a newer status set in the meantime isn't
     *  clobbered by a stale clear from an earlier call. */
    private fun MutableStateFlow<String?>.showTemporarily(message: String?) {
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

    fun saveMusicFolder(uri: Uri) {
        // Backup first, then persist: the snapshot must capture the library as it was
        // under the old folder, before the change invalidates it. Validation precedes even
        // that — a rejected pick must leave no trace, backup included.
        launchCatching(onError = {
            _folderError.showTemporarily(context.getString(R.string.error_folder_save_failed))
        }) {
            val rejected = rejectIfNested(
                candidate = uri,
                other = _newArrivalsFolderUri.value,
                onError = { _folderError.value = it },
            )
            if (rejected) return@launchCatching

            backupManager.createBackup(BackupTier.Auto, BackupReason.FOLDER_CHANGE)

            releaseGrantIfSuperseded(old = _musicFolderUri.value, new = uri)
            preferencesManager.musicFolderUri = uri.toString()
            _musicFolderUri.value = uri
            clearFolderError()
            _localStatus.value = null
            // REPLACE, not KEEP: any scan still running is walking the folder the user just
            // replaced, so its results are already stale.
            LibraryScanWorker.enqueue(context, ExistingWorkPolicy.REPLACE)
        }
    }

    /**
     * Deliberately diverges from [saveMusicFolder]: no [BackupReason.FOLDER_CHANGE] snapshot
     * (nothing about the existing library is invalidated by pointing at a different arrivals
     * tree) and, critically, no [ExistingWorkPolicy.REPLACE] — the shared unique work name may
     * be carrying an in-flight main-root scan, which must not be cancelled by this. The KEEP
     * enqueue just gets the freshly-picked folder ingested now rather than at next launch; if
     * something is already running it's a no-op and the launch chain picks it up next time.
     */
    fun saveNewArrivalsFolder(uri: Uri) {
        launchCatching(onError = {
            _newArrivalsFolderError.showTemporarily(context.getString(R.string.error_folder_save_failed))
        }) {
            val rejected = rejectIfNested(
                candidate = uri,
                other = _musicFolderUri.value,
                onError = { _newArrivalsFolderError.value = it },
            )
            if (rejected) return@launchCatching

            releaseGrantIfSuperseded(old = _newArrivalsFolderUri.value, new = uri)
            preferencesManager.newArrivalsFolderUri = uri.toString()
            _newArrivalsFolderUri.value = uri
            _newArrivalsFolderError.value = null
            NewArrivalsScanWorker.enqueue(context)
        }
    }

    /**
     * Guards both audio-root pickers against overlapping trees — see [RootNesting] for why
     * nesting is unrecoverable rather than merely untidy. Returns `true` when the pick was
     * rejected, in which case nothing is persisted and the previously configured URI stands
     * unchanged; the caller must return without saving.
     *
     * The grant the picker just took for the rejected URI is released here, since nothing
     * will ever use it. The *old* root's grant is untouched — this runs before
     * [releaseGrantIfSuperseded], which is exactly why grant release had to move out of the
     * picker callbacks and behind validation.
     */
    private suspend fun rejectIfNested(
        candidate: Uri,
        other: Uri?,
        onError: (String) -> Unit,
    ): Boolean {
        if (other == null) return false
        if (!RootNesting.detect(context, candidate, other).isConflict) return false

        onError(context.getString(R.string.error_folders_nested))
        releaseGrant(candidate)
        return true
    }

    /** No-op when the user re-picked the folder already configured — releasing then would
     *  drop the grant the new pick depends on, since both are the same URI. */
    private fun releaseGrantIfSuperseded(old: Uri?, new: Uri) {
        if (old != null && old != new) releaseGrant(old)
    }

    private fun releaseGrant(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Already gone — nothing to release
        }
    }

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
