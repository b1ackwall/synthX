package com.v1per.synthx.ui.screens.developersettings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.playlistseed.PlaylistSeedBootstrapResult
import com.v1per.synthx.data.playlistseed.PlaylistSeedBootstrapper
import com.v1per.synthx.data.playlistseed.PlaylistSeedManifestGenerator
import com.v1per.synthx.data.playlistseed.PlaylistSeedManifestRegenerationResult
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.RootOrganizationCheckResult
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeveloperSettingsViewModel internal constructor(
    private val playlistSeedBootstrapper: PlaylistSeedBootstrapper,
    private val playlistSeedManifestGenerator: PlaylistSeedManifestGenerator,
    private val repository: SongRepository,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    private val _isSeeding = MutableStateFlow(false)
    val isSeeding: StateFlow<Boolean> = _isSeeding.asStateFlow()

    private val _seedResult = MutableStateFlow<PlaylistSeedBootstrapResult?>(null)
    val seedResult: StateFlow<PlaylistSeedBootstrapResult?> = _seedResult.asStateFlow()

    private val _isRegeneratingSeedManifest = MutableStateFlow(false)
    val isRegeneratingSeedManifest: StateFlow<Boolean> = _isRegeneratingSeedManifest.asStateFlow()

    private val _regenerateSeedManifestResult =
        MutableStateFlow<PlaylistSeedManifestRegenerationResult?>(null)
    val regenerateSeedManifestResult: StateFlow<PlaylistSeedManifestRegenerationResult?> =
        _regenerateSeedManifestResult.asStateFlow()

    private val _rootOrganizationCheckStatus = MutableStateFlow<String?>(null)
    val rootOrganizationCheckStatus: StateFlow<String?> =
        _rootOrganizationCheckStatus.asStateFlow()

    private val _isCheckingRootOrganization = MutableStateFlow(false)
    val isCheckingRootOrganization: StateFlow<Boolean> =
        _isCheckingRootOrganization.asStateFlow()

    private val _rootOrganizationCheckResult = MutableStateFlow<RootOrganizationCheckResult?>(null)
    val rootOrganizationCheckResult: StateFlow<RootOrganizationCheckResult?> =
        _rootOrganizationCheckResult.asStateFlow()

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

    fun regenerateSeedManifest() {
        if (_isRegeneratingSeedManifest.value) return

        viewModelScope.launch {
            _isRegeneratingSeedManifest.value = true
            try {
                _regenerateSeedManifestResult.value = playlistSeedManifestGenerator.regenerate()
            } finally {
                _isRegeneratingSeedManifest.value = false
            }
        }
    }

    fun dismissRegenerateSeedManifestResult() {
        _regenerateSeedManifestResult.value = null
    }

    fun checkRootLibraryOrganization() {
        if (_isCheckingRootOrganization.value) return

        viewModelScope.launch {
            _isCheckingRootOrganization.value = true
            try {
                val musicFolderUri = preferencesManager.musicFolderUri?.let(Uri::parse)
                if (musicFolderUri == null) {
                    _rootOrganizationCheckStatus.showTemporarily("No music folder configured")
                    return@launch
                }
                val result = repository.checkRootLibraryOrganization(musicFolderUri)
                if (result == null) {
                    _rootOrganizationCheckStatus.showTemporarily("Music folder not accessible")
                } else {
                    _rootOrganizationCheckResult.value = result
                }
            } finally {
                _isCheckingRootOrganization.value = false
            }
        }
    }

    fun dismissRootOrganizationCheckResult() {
        _rootOrganizationCheckResult.value = null
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
                playlistSeedManifestGenerator = PlaylistSeedManifestGenerator.create(appContext),
                repository = SongRepositoryImpl.create(appContext),
                preferencesManager = PreferencesManager(appContext),
            ) as T
        }
    }

    private companion object {
        const val STATUS_MESSAGE_DISPLAY_MS = 4_000L
    }
}
