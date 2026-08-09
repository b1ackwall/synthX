package com.v1per.synthx.ui.screens.issues

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.DeleteSongsGate
import com.v1per.synthx.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class IssuesViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<MissingSongInfo>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<MissingSongInfo>>> = _uiState.asStateFlow()

    /** Gates the delete row in the menu below — see [DeleteSongsGate]. */
    val deleteSongsEnabled: StateFlow<Boolean> = DeleteSongsGate.deleteSongsEnabled

    init {
        viewModelScope.launch {
            repository.getMissingSongs()
                .catch { e ->
                    _uiState.value = UiState.Error(e.message ?: "Failed to load issues")
                }
                .collect { songs ->
                    _uiState.value =
                        if (songs.isEmpty()) UiState.Empty else UiState.Success(data = songs)
                }
        }
    }

    // The list is Flow-backed, so Room's invalidation drops the row from the UI on its own.
    fun deleteSong(id: String) {
        viewModelScope.launch {
            repository.deleteSong(id)
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(IssuesViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return IssuesViewModel(SongRepositoryImpl.create(context)) as T
        }
    }
}
