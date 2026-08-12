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
import com.v1per.synthx.data.backup.RestoreWorker
import com.v1per.synthx.ui.common.UiState
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

class RestoreViewModel(
    private val context: Context,
    private val backupManager: BackupManager,
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

    val outcome: StateFlow<RestoreOutcome> = combine(workInfo, _dismissedFailure) { info, dismissed ->
        when {
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

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = try {
                val backups = backupManager.listBackups()
                if (backups.isEmpty()) UiState.Empty else UiState.Success(backups)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to read backups")
            }
        }
    }

    fun restore(backup: BackupInfo) {
        // ExistingWorkPolicy.KEEP on the enqueue itself already makes a second call while
        // one is in flight a no-op at the WorkManager level; this mirrors that at the UI
        // level too so a double-tap doesn't even reach the enqueue call, and resets the
        // dismissal flag so a stale acknowledged failure from a previous attempt doesn't
        // suppress this attempt's own outcome once it lands.
        if (outcome.value == RestoreOutcome.InProgress) return
        _dismissedFailure.value = false
        RestoreWorker.enqueue(context, backup)
    }

    fun dismissFailure() {
        if (outcome.value is RestoreOutcome.Failed) _dismissedFailure.value = true
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(RestoreViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return RestoreViewModel(appContext, BackupManager.create(appContext)) as T
        }
    }
}
