package com.v1per.synthx.ui.screens.restore

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupPreviewCounts
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Terminal step of a restore — the process must be killed once this is reached. */
sealed class RestoreOutcome {
    object Idle : RestoreOutcome()
    object InProgress : RestoreOutcome()
    object Complete : RestoreOutcome()
    data class Failed(val message: String) : RestoreOutcome()
}

/** P4.3 dry-run preview shown before the destructive confirm dialog. */
sealed class RestorePreview {
    object Idle : RestorePreview()
    data class Loading(val backup: BackupInfo) : RestorePreview()
    data class Ready(val backup: BackupInfo, val counts: BackupPreviewCounts) : RestorePreview()
    data class Failed(val backup: BackupInfo, val message: String) : RestorePreview()
}

class RestoreViewModel(
    private val context: Context,
    private val backupManager: BackupManager,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    private val _uiState = MutableStateFlow<UiState<List<BackupInfo>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<BackupInfo>>> = _uiState.asStateFlow()

    // The single source of truth for restore state — reading it from WorkManager (rather
    // than a local flow the ViewModel owns) is what makes an in-flight restore survive
    // navigating away from this screen and back, the same reason
    // LibraryManagementViewModel.workInfo reads scan state from WorkManager.
    private val workInfo: StateFlow<WorkInfo?> = workManager
        .getWorkInfosForUniqueWorkFlow(RestoreWorker.UNIQUE_WORK_NAME)
        .map { infos -> infos.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // WorkManager retains a unique work item's terminal WorkInfo after it finishes (until a
    // new enqueue replaces it or the record is pruned) — without this, dismissing a Failed
    // dialog would have nothing to change, since the underlying WorkInfo stays FAILED.
    private val _dismissedFailure = MutableStateFlow(false)

    // Set by restore() when it refuses to enqueue (currently: no root music folder configured
    // — see BackupsAndRestoreScreen's primary gate for the same check earlier in the flow).
    // Folded into outcome below via the existing Failed rendering rather than a second error
    // channel, so RestoreScreen needs no new UI to surface this.
    private val _localBlockReason = MutableStateFlow<String?>(null)

    val outcome: StateFlow<RestoreOutcome> = combine(
        workInfo,
        _dismissedFailure,
        _localBlockReason,
    ) { info, dismissed, blockReason ->
        when {
            blockReason != null -> RestoreOutcome.Failed(blockReason)
            info == null -> RestoreOutcome.Idle
            !info.state.isFinished -> RestoreOutcome.InProgress
            info.state == WorkInfo.State.SUCCEEDED -> RestoreOutcome.Complete
            info.state == WorkInfo.State.FAILED -> if (dismissed) {
                RestoreOutcome.Idle
            } else {
                RestoreOutcome.Failed(
                    info.outputData.getString(RestoreWorker.KEY_ERROR)
                        ?: context.getString(R.string.restore_error_unknown),
                )
            }
            info.state == WorkInfo.State.CANCELLED -> if (dismissed) {
                RestoreOutcome.Idle
            } else {
                RestoreOutcome.Failed(context.getString(R.string.restore_error_cancelled))
            }
            else -> RestoreOutcome.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RestoreOutcome.Idle)

    private val _preview = MutableStateFlow<RestorePreview>(RestorePreview.Idle)
    val preview: StateFlow<RestorePreview> = _preview.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        // Recovery can change the backup folder while an earlier SAF listing is still running.
        refreshJob?.cancel()
        _uiState.value = UiState.Loading
        refreshJob = viewModelScope.launch {
            val result = try {
                val backups = backupManager.listBackups()
                if (backups.isEmpty()) UiState.Empty else UiState.Success(backups)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to read backups")
            }
            ensureActive()
            _uiState.value = result
        }
    }

    /** Stages [backup] locally and counts its contents — see [BackupManager.previewBackup]. */
    fun beginPreview(backup: BackupInfo) {
        _preview.value = RestorePreview.Loading(backup)
        viewModelScope.launch {
            backupManager.previewBackup(backup).fold(
                onSuccess = { counts -> _preview.value = RestorePreview.Ready(backup, counts) },
                onFailure = { error ->
                    _preview.value = RestorePreview.Failed(
                        backup,
                        error.message ?: context.getString(R.string.restore_preview_error_unknown),
                    )
                },
            )
        }
    }

    fun dismissPreview() {
        _preview.value = RestorePreview.Idle
    }

    /** [onInitiated] runs after submission, before the app shell can dispose this route. */
    fun restore(backup: BackupInfo, onInitiated: () -> Unit = {}) {
        // ExistingWorkPolicy.KEEP on the enqueue itself already makes a second call while
        // one is in flight a no-op at the WorkManager level; this mirrors that at the UI
        // level too so a double-tap doesn't even reach the enqueue call, and resets the
        // dismissal flag so a stale acknowledged failure from a previous attempt doesn't
        // suppress this attempt's own outcome once it lands.
        if (outcome.value == RestoreOutcome.InProgress) return

        // Backstop for both the settings screen and onboarding: never enqueue for a library
        // with nowhere configured to find its songs, regardless of how this got reached.
        if (preferencesManager.musicFolderUri == null) {
            _dismissedFailure.value = false
            _localBlockReason.value = context.getString(R.string.restore_error_music_folder_required)
            return
        }

        _dismissedFailure.value = false
        _localBlockReason.value = null
        RestoreWorker.enqueue(context, backup)
        onInitiated()
    }

    fun dismissFailure() {
        if (outcome.value is RestoreOutcome.Failed) {
            _dismissedFailure.value = true
            _localBlockReason.value = null
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(RestoreViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return RestoreViewModel(
                appContext,
                BackupManager.create(appContext),
                PreferencesManager(appContext),
            ) as T
        }
    }
}
