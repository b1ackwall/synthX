package com.v1per.synthx.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    private val _musicFolderUri = MutableStateFlow(
        preferencesManager.musicFolderUri?.let { Uri.parse(it) }
    )
    val musicFolderUri: StateFlow<Uri?> = _musicFolderUri.asStateFlow()

    fun saveMusicFolder(uri: Uri) {
        preferencesManager.musicFolderUri = uri.toString()
        _musicFolderUri.value = uri
    }

    class Factory(
        private val preferencesManager: PreferencesManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(preferencesManager) as T
    }
}
