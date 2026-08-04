package com.v1per.synthx.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val repository: SongRepository,
) : ViewModel() {

    private val _musicFolderUri = MutableStateFlow(
        preferencesManager.musicFolderUri?.let { Uri.parse(it) }
    )
    val musicFolderUri: StateFlow<Uri?> = _musicFolderUri.asStateFlow()

    private val _rescanStatus = MutableStateFlow<String?>(null)
    val rescanStatus: StateFlow<String?> = _rescanStatus.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()

    fun setFolderError(message: String) {
        _folderError.value = message
    }

    fun clearFolderError() {
        _folderError.value = null
    }

    fun saveMusicFolder(uri: Uri) {
        preferencesManager.musicFolderUri = uri.toString()
        _musicFolderUri.value = uri
        clearFolderError()
        // Newly picked folder — populate the library right away
        rescanLibrary()
    }

    fun rescanLibrary() {
        if (_isScanning.value) return // guard against double-tap
        val folderUri = _musicFolderUri.value ?: run {
            _rescanStatus.value = "No folder selected"
            return
        }
        // A stored URI outlives its grant (revoked access, restored backup). Scanning
        // without the grant reports a successful empty scan and flags the whole
        // library missing, so drop the stale URI and prompt for a re-pick instead.
        if (!isGrantValid(folderUri)) {
            preferencesManager.musicFolderUri = null
            _musicFolderUri.value = null
            _folderError.value =
                "Access to your music folder has been revoked. Please select it again."
            return
        }
        // Set synchronously, before the launch: rescanLibrary is called from the main
        // thread, so a second tap would otherwise pass the guard above before the
        // first coroutine body runs.
        _isScanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _rescanStatus.value = "Scanning…"
            try {
                val result = repository.scanLibrary(folderUri)
                _rescanStatus.value =
                    "Done — ${result.added} added, ${result.updated} updated, " +
                        "${result.missing} missing"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _rescanStatus.value = "Scan failed: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun isGrantValid(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(
                context = appContext,
                preferencesManager = PreferencesManager(appContext),
                repository = SongRepositoryImpl.create(appContext),
            ) as T
        }
    }
}
