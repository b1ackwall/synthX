package com.v1per.synthx.ui.screens.librarymanagement

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        local ?: statusFor(info)
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
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Scanning…"
        WorkInfo.State.RUNNING -> {
            val current = info.progress.getInt(LibraryScanWorker.KEY_CURRENT, -1)
            val total = info.progress.getInt(LibraryScanWorker.KEY_TOTAL, -1)
            if (total > 0) "Scanning… $current of $total" else "Scanning…"
        }
        WorkInfo.State.SUCCEEDED -> {
            val data = info.outputData
            "Done — ${data.getInt(LibraryScanWorker.KEY_ADDED, 0)} added, " +
                "${data.getInt(LibraryScanWorker.KEY_UPDATED, 0)} updated, " +
                "${data.getInt(LibraryScanWorker.KEY_MISSING, 0)} missing"
        }
        WorkInfo.State.FAILED ->
            "Scan failed: ${info.outputData.getString(LibraryScanWorker.KEY_ERROR) ?: "unknown error"}"
        WorkInfo.State.CANCELLED -> "Scan cancelled"
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
            _backupStatus.value = if (created) null else "Couldn't create backup folders"
        }
    }

    fun createManualBackup() {
        viewModelScope.launch {
            _backupStatus.value = "Backing up…"
            val result = backupManager.createBackup(BackupTier.Manual)
            _backupStatus.value = result.fold(
                onSuccess = { "Backup created" },
                onFailure = { e -> "Backup failed: ${e.message ?: "unknown error"}" },
            )
        }
    }

    fun rescanLibrary() {
        if (_musicFolderUri.value == null) {
            _localStatus.value = "No folder selected"
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
