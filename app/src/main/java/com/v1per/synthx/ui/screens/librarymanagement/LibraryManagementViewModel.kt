package com.v1per.synthx.ui.screens.librarymanagement

import android.content.Context
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
import com.v1per.synthx.data.seedPlaylistsFromFolders
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.preferences.PreferencesManager
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

/** How long a terminal status message (e.g. "Backup created", "No folder selected") stays
 *  visible before auto-clearing, matching typical snackbar/status-message durations. */
private const val StatusMessageDisplayMs = 4_000L

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

    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()

    private val _backupFolderUri = MutableStateFlow(
        preferencesManager.backupFolderUri?.let { Uri.parse(it) }
    )
    val backupFolderUri: StateFlow<Uri?> = _backupFolderUri.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    /** Thin passthrough to the shared in-memory gate — see [DeleteSongsGate]. */
    val deleteSongsEnabled: StateFlow<Boolean> = DeleteSongsGate.deleteSongsEnabled

    // Temporary feature — see PlaylistSeeder.kt's doc comment for the full removal
    // checklist. This state plus seedPlaylists() below are the only ViewModel touch
    // points.
    private val _seedPlaylistsStatus = MutableStateFlow<String?>(null)
    val seedPlaylistsStatus: StateFlow<String?> = _seedPlaylistsStatus.asStateFlow()

    // Guards against a double-tap enqueuing a second concurrent run — there's no
    // WorkManager-style unique-work dedupe here, so this is the only thing preventing
    // two seedPlaylistsFromFolders calls from racing on the same playlists.
    private val _isSeeding = MutableStateFlow(false)
    val isSeeding: StateFlow<Boolean> = _isSeeding.asStateFlow()

    // Set only for states WorkManager can't express — currently just "no folder picked",
    // which never reaches the worker. Cleared as soon as a scan is actually enqueued.
    private val _localStatus = MutableStateFlow<String?>(null)

    // The single source of truth for scan state. Reading it from WorkManager rather than
    // from local flows is what makes progress survive navigation and app restart: a
    // freshly constructed ViewModel observing a scan already in flight sees its real
    // state, not a default.
    private val workInfo: StateFlow<WorkInfo?> = workManager
        .getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_WORK_NAME)
        .map { infos -> infos.firstOrNull() }
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
        // The worker clears the stored URI when it finds the grant revoked. Mirror that
        // back into the UI so the folder row stops showing a path the app can't read.
        viewModelScope.launch {
            workInfo.collect { info ->
                if (info?.state == WorkInfo.State.FAILED) {
                    _musicFolderUri.value = preferencesManager.musicFolderUri?.let { Uri.parse(it) }
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
            context.getString(
                R.string.scan_status_done,
                data.getInt(LibraryScanWorker.KEY_ADDED, 0),
                data.getInt(LibraryScanWorker.KEY_UPDATED, 0),
                data.getInt(LibraryScanWorker.KEY_MISSING, 0),
            )
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

    fun saveMusicFolder(uri: Uri) {
        // Backup first, then persist: the snapshot must capture the library as it was
        // under the old folder, before the change invalidates it.
        viewModelScope.launch {
            backupManager.createBackup(BackupTier.Auto, BackupReason.FOLDER_CHANGE)

            preferencesManager.musicFolderUri = uri.toString()
            _musicFolderUri.value = uri
            clearFolderError()
            _localStatus.value = null
            // REPLACE, not KEEP: any scan still running is walking the folder the user just
            // replaced, so its results are already stale.
            LibraryScanWorker.enqueue(context, ExistingWorkPolicy.REPLACE)
        }
    }

    fun saveBackupFolder(uri: Uri) {
        preferencesManager.backupFolderUri = uri.toString()
        _backupFolderUri.value = uri
        viewModelScope.launch {
            val created = backupManager.ensureFolderStructure(uri)
            if (created) {
                _backupStatus.value = null
            } else {
                _backupStatus.showTemporarily(context.getString(R.string.backup_status_folder_create_failed))
            }
        }
    }

    fun createManualBackup() {
        viewModelScope.launch {
            _backupStatus.value = context.getString(R.string.backup_status_backing_up)
            val result = backupManager.createBackup(BackupTier.Manual)
            _backupStatus.showTemporarily(
                result.fold(
                    onSuccess = { context.getString(R.string.backup_status_created) },
                    onFailure = { e ->
                        context.getString(
                            R.string.backup_status_failed,
                            e.message ?: context.getString(R.string.scan_status_unknown_error),
                        )
                    },
                )
            )
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

    /**
     * Temporary feature — safe to delete. Removing this function (plus the
     * [_seedPlaylistsStatus]/[seedPlaylistsStatus] and [_isSeeding]/[isSeeding] state
     * above it) and its four other touch points fully removes "Seed Playlists
     * (Temporary)" with no other cleanup needed:
     *   1. data/PlaylistSeeder.kt
     *   2. ui/screens/librarymanagement/SeedPlaylistsSection.kt
     *   3. This function, plus the state fields noted above (LibraryManagementViewModel.kt)
     *   4. The `SeedPlaylistsSection(...)` call in LibraryManagementScreenContent, and its
     *      `seedPlaylistsStatus`/`isSeeding` params (LibraryManagementScreen.kt)
     *   5. The `seedPlaylistsStatus`/`isSeeding`/`onSeedPlaylists` args added to the three
     *      `LibraryManagementScreenContent(...)` preview call sites
     *      (LibraryManagementScreenPreviews.kt)
     */
    fun seedPlaylists() {
        if (_isSeeding.value) return // already running — ignore a double-tap

        val folderUri = _musicFolderUri.value
        if (folderUri == null) {
            _seedPlaylistsStatus.showTemporarily(context.getString(R.string.settings_no_folder_selected))
            return
        }
        viewModelScope.launch {
            _isSeeding.value = true
            _seedPlaylistsStatus.value = "Seeding…"
            try {
                val result = seedPlaylistsFromFolders(context, folderUri, repository) { progress ->
                    _seedPlaylistsStatus.value = "Seeding… folder ${progress.foldersProcessed}/" +
                        "${progress.totalFolders} (${progress.songsMatchedSoFar} songs matched)"
                }
                _seedPlaylistsStatus.showTemporarily(
                    "Created ${result.playlistsCreated}, updated ${result.playlistsUpdated} — " +
                        "${result.songsMatched} songs matched, ${result.filesUnmatched} unmatched"
                )
            } finally {
                _isSeeding.value = false
            }
        }
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
