package com.v1per.synthx.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesManager: PreferencesManager,
    private val repository: SongRepository,
) : ViewModel() {

    private val _musicFolderUri = MutableStateFlow(
        preferencesManager.musicFolderUri?.let { Uri.parse(it) }
    )
    val musicFolderUri: StateFlow<Uri?> = _musicFolderUri.asStateFlow()

    private val _rescanStatus = MutableStateFlow<String?>(null)
    val rescanStatus: StateFlow<String?> = _rescanStatus.asStateFlow()

    fun saveMusicFolder(uri: Uri) {
        preferencesManager.musicFolderUri = uri.toString()
        _musicFolderUri.value = uri
        // Newly picked folder — populate the library right away
        rescanLibrary()
    }

    fun rescanLibrary() {
        val folderUri = _musicFolderUri.value ?: run {
            _rescanStatus.value = "No folder selected"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _rescanStatus.value = "Scanning…"
            try {
                val result = repository.scanLibrary(folderUri)
                _rescanStatus.value =
                    "Done — ${result.added} added, ${result.updated} updated, " +
                        "${result.missing} missing"
            } catch (e: Exception) {
                _rescanStatus.value = "Scan failed"
            }
        }
    }

    class Factory(
        private val preferencesManager: PreferencesManager,
        private val repository: SongRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(preferencesManager, repository) as T
    }
}
