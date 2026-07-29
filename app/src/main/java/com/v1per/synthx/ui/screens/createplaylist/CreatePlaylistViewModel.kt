package com.v1per.synthx.ui.screens.createplaylist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CreatePlaylistViewModel(
    private val repository: SongRepository,
) : ViewModel() {

    private val _groups = MutableStateFlow<List<PlaylistGroup>>(emptyList())
    val groups: StateFlow<List<PlaylistGroup>> = _groups.asStateFlow()

    private val _created = MutableStateFlow(false)
    val created: StateFlow<Boolean> = _created.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllGroups().collect { _groups.value = it }
        }
    }

    fun createPlaylist(name: String, groupId: String?) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createPlaylist(name.trim(), groupId)
            _created.value = true
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = SongRepositoryImpl.create(context)
                    @Suppress("UNCHECKED_CAST")
                    return CreatePlaylistViewModel(repository) as T
                }
            }
    }
}
