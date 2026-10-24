package com.v1per.synthx.ui.screens.createplaylist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

// Kept as plain strings rather than string resources: this ViewModel has no Context (its
// Factory only needs a SongRepository), and threading one in solely to resolve two error
// strings isn't worth the extra constructor parameter. Screen-layer ViewModels elsewhere
// that already hold a Context resolve through strings.xml instead —
// this is the one exception, not the new pattern.
private const val GENERIC_LOAD_ERROR = "Couldn’t load your groups. Try again."
private const val GENERIC_CREATE_ERROR = "Couldn’t create this playlist. Try again."

class CreatePlaylistViewModel(private val repository: SongRepository) : ViewModel() {

    // Groups-loading readiness, consumed by PlaylistRoutes' ReadinessCoverOverlay — kept
    // separate from `error` below, which is about create-action failures, not load failures.
    private val _uiState = MutableStateFlow<UiState<List<PlaylistGroup>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<PlaylistGroup>>> = _uiState.asStateFlow()

    private val _created = MutableStateFlow(false)
    val created: StateFlow<Boolean> = _created.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Guards against a double-tap firing createPlaylist twice while the first write is
    // still in flight — the screen also disables the button while this is true.
    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllGroups()
                .catch { _uiState.value = UiState.Error(GENERIC_LOAD_ERROR) }
                .collect { groups ->
                    _uiState.value = if (groups.isEmpty()) UiState.Empty else UiState.Success(data = groups)
                }
        }
    }

    fun createPlaylist(name: String, groupId: String?) {
        if (name.isBlank() || _isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            try {
                repository.createPlaylist(name.trim(), groupId)
                _error.value = null
                _created.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = GENERIC_CREATE_ERROR
            } finally {
                _isCreating.value = false
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = SongRepositoryImpl.create(context)
                // Class<T> is erased; this factory only ever builds CreatePlaylistViewModel.
                @Suppress("UNCHECKED_CAST")
                return CreatePlaylistViewModel(repository) as T
            }
        }
    }
}
