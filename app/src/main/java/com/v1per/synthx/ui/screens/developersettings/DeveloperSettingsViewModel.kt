package com.v1per.synthx.ui.screens.developersettings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.playlistseed.PlaylistSeedBootstrapResult
import com.v1per.synthx.data.playlistseed.PlaylistSeedBootstrapper
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeveloperSettingsViewModel internal constructor(
    private val playlistSeedBootstrapper: PlaylistSeedBootstrapper,
    private val repository: SongRepository,
) : ViewModel() {

    private val _isSeeding = MutableStateFlow(false)
    val isSeeding: StateFlow<Boolean> = _isSeeding.asStateFlow()

    private val _seedResult = MutableStateFlow<PlaylistSeedBootstrapResult?>(null)
    val seedResult: StateFlow<PlaylistSeedBootstrapResult?> = _seedResult.asStateFlow()

    private val _removeOrganizedFromStarredStatus = MutableStateFlow<String?>(null)
    val removeOrganizedFromStarredStatus: StateFlow<String?> =
        _removeOrganizedFromStarredStatus.asStateFlow()

    private val _isRemovingOrganizedFromStarred = MutableStateFlow(false)
    val isRemovingOrganizedFromStarred: StateFlow<Boolean> =
        _isRemovingOrganizedFromStarred.asStateFlow()

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

    fun removeOrganizedFromStarred() {
        if (_isRemovingOrganizedFromStarred.value) return

        viewModelScope.launch {
            _isRemovingOrganizedFromStarred.value = true
            try {
                val count = repository.removeOrganizedFromStarred()
                _removeOrganizedFromStarredStatus.showTemporarily(
                    if (count > 0) {
                        "Removed $count songs from Starred"
                    } else {
                        "No organized songs to remove"
                    }
                )
            } finally {
                _isRemovingOrganizedFromStarred.value = false
            }
        }
    }

    private fun MutableStateFlow<String?>.showTemporarily(message: String?) {
        value = message
        viewModelScope.launch {
            delay(STATUS_MESSAGE_DISPLAY_MS)
            if (value == message) value = null
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(DeveloperSettingsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return DeveloperSettingsViewModel(
                playlistSeedBootstrapper = PlaylistSeedBootstrapper.create(
                    appContext,
                ),
                repository = SongRepositoryImpl.create(appContext),
            ) as T
        }
    }

    private companion object {
        const val STATUS_MESSAGE_DISPLAY_MS = 4_000L
    }
}
