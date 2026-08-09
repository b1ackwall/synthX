package com.v1per.synthx.preferences

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure in-memory safety toggle gating the "delete song" action, read by IssuesViewModel
 * and SongActionsViewModel and written by LibraryManagementViewModel. PreferencesManager
 * is constructed fresh per call site rather than shared, so a field on it would not be
 * visible across those three — this object is the actual shared instance instead.
 * Deliberately unpersisted: resets to false on every process start.
 */
object DeleteSongsGate {
    private val _deleteSongsEnabled = MutableStateFlow(false)
    val deleteSongsEnabled: StateFlow<Boolean> = _deleteSongsEnabled.asStateFlow()

    fun setDeleteSongsEnabled(value: Boolean) {
        _deleteSongsEnabled.value = value
    }
}
