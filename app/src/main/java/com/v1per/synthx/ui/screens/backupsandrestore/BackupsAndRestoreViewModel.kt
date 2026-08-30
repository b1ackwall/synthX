package com.v1per.synthx.ui.screens.backupsandrestore

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupFolderHealth
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.scanner.RootNesting
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

    init {
        // Re-validate on every screen entry (P7.3) — a fresh ViewModel is constructed each
        // time this screen is opened, so this doubles as "check whenever the user looks."
        // Persistent (not showTemporarily): a broken folder should stay visible until the
        // user fixes it, not vanish after 4s while nothing has actually changed.
        preferencesManager.backupFolderUri?.let { Uri.parse(it) }?.let { uri ->
            viewModelScope.launch {
                when (BackupFolderHealth.check(context, backupManager, uri)) {
                    BackupFolderHealth.Result.Healthy -> Unit
                    BackupFolderHealth.Result.GrantLost ->
                        _backupFolderError.value =
                            context.getString(R.string.error_backup_folder_grant_lost)
                    BackupFolderHealth.Result.Unwritable ->
                        _backupFolderError.value =
                            context.getString(R.string.backup_status_folder_create_failed)
                }
            }
        }
    }

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

    /**
     * Validates before persisting anything (data 2.5): a folder that fails nesting or
     * structure validation, or throws, must never reach [PreferencesManager.backupFolderUri].
     * The whole body runs inside this one coroutine's own try/catch (general 7.4) — nothing
     * here is left to a scope that outlives the picker callback's own (now-closed) catch.
     */
    fun saveBackupFolder(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val nestsAudioRoot = preferencesManager.configuredAudioRootUris().any { audioRoot ->
                    RootNesting.detect(context, uri, audioRoot).isConflict
                }
                if (nestsAudioRoot) {
                    return@runCatching Outcome.NESTED
                }
                if (backupManager.ensureFolderStructure(uri)) Outcome.OK else Outcome.UNUSABLE
            }.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        Outcome.OK -> {
                            preferencesManager.backupFolderUri = uri.toString()
                            _backupFolderUri.value = uri
                            _backupFolderError.value = null
                            _backupStatus.value = null
                        }
                        Outcome.NESTED -> _backupFolderError.showTemporarily(
                            context.getString(R.string.error_backup_folder_nested)
                        )
                        Outcome.UNUSABLE -> _backupFolderError.showTemporarily(
                            context.getString(R.string.backup_status_folder_create_failed)
                        )
                    }
                },
                onFailure = {
                    _backupFolderError.showTemporarily(
                        context.getString(R.string.backup_status_folder_check_failed)
                    )
                },
            )
        }
    }

    private enum class Outcome { OK, NESTED, UNUSABLE }

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
