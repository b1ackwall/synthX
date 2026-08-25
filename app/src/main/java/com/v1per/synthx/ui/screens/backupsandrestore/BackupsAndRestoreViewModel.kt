package com.v1per.synthx.ui.screens.backupsandrestore

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val StatusMessageDisplayMs = 4_000L

class BackupsAndRestoreViewModel(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _backupFolderUri = MutableStateFlow(
        preferencesManager.backupFolderUri?.let { Uri.parse(it) }
    )
    val backupFolderUri: StateFlow<Uri?> = _backupFolderUri.asStateFlow()

    private val _backupFolderError = MutableStateFlow<String?>(null)
    val backupFolderError: StateFlow<String?> = _backupFolderError.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    private fun MutableStateFlow<String?>.showTemporarily(message: String?) {
        value = message
        viewModelScope.launch {
            delay(StatusMessageDisplayMs)
            if (value == message) value = null
        }
    }

    fun setBackupFolderError(message: String) {
        _backupFolderError.value = message
    }

    fun saveBackupFolder(uri: Uri) {
        preferencesManager.backupFolderUri = uri.toString()
        _backupFolderUri.value = uri
        _backupFolderError.value = null
        viewModelScope.launch {
            val created = backupManager.ensureFolderStructure(uri)
            if (created) {
                _backupFolderError.value = null
                _backupStatus.value = null
            } else {
                _backupFolderError.showTemporarily(
                    context.getString(R.string.backup_status_folder_create_failed)
                )
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

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(BackupsAndRestoreViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return BackupsAndRestoreViewModel(
                context = appContext,
                preferencesManager = PreferencesManager(appContext),
                backupManager = BackupManager.create(appContext),
            ) as T
        }
    }
}
