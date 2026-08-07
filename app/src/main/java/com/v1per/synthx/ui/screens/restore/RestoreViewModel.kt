package com.v1per.synthx.ui.screens.restore

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Terminal step of a restore — the process must be killed once this is reached. */
sealed class RestoreOutcome {
    object Idle : RestoreOutcome()
    object InProgress : RestoreOutcome()
    object Complete : RestoreOutcome()
    data class Failed(val message: String) : RestoreOutcome()
}

class RestoreViewModel(
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<BackupInfo>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<BackupInfo>>> = _uiState.asStateFlow()

    private val _outcome = MutableStateFlow<RestoreOutcome>(RestoreOutcome.Idle)
    val outcome: StateFlow<RestoreOutcome> = _outcome.asStateFlow()

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
        if (_outcome.value == RestoreOutcome.InProgress) return
        viewModelScope.launch {
            _outcome.value = RestoreOutcome.InProgress

            // Safety net first — if the chosen backup turns out to be wrong, the state
            // being replaced is still recoverable from the Auto tier.
            backupManager.createBackup(BackupTier.Auto, BackupReason.PRE_RESTORE_SAFETY)

            _outcome.value = backupManager.restore(backup).fold(
                onSuccess = { RestoreOutcome.Complete },
                onFailure = { e -> RestoreOutcome.Failed(e.message ?: "Restore failed") },
            )
        }
    }

    fun dismissFailure() {
        if (_outcome.value is RestoreOutcome.Failed) _outcome.value = RestoreOutcome.Idle
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(RestoreViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return RestoreViewModel(BackupManager.create(context)) as T
        }
    }
}
