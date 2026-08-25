package com.v1per.synthx.ui.screens.developersettings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.playlistseed.PlaylistSeedBootstrapResult
import com.v1per.synthx.data.playlistseed.PlaylistSeedBootstrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeveloperSettingsViewModel internal constructor(
    private val playlistSeedBootstrapper: PlaylistSeedBootstrapper,
) : ViewModel() {

    private val _isSeeding = MutableStateFlow(false)
    val isSeeding: StateFlow<Boolean> = _isSeeding.asStateFlow()

    private val _seedResult = MutableStateFlow<PlaylistSeedBootstrapResult?>(null)
    val seedResult: StateFlow<PlaylistSeedBootstrapResult?> = _seedResult.asStateFlow()

    fun seedPlaylistsFromManifest() {
        if (_isSeeding.value) return

        viewModelScope.launch {
            _isSeeding.value = true
            try {
                _seedResult.value = playlistSeedBootstrapper.seed()
            } finally {
                _isSeeding.value = false
            }
        }
    }

    fun dismissSeedResult() {
        _seedResult.value = null
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(DeveloperSettingsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return DeveloperSettingsViewModel(
                playlistSeedBootstrapper = PlaylistSeedBootstrapper.create(
                    context.applicationContext,
                ),
            ) as T
        }
    }
}
