package com.v1per.synthx.ui.common

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    object Empty : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

// Used to gate destination-level readiness covers — anything past the initial load,
// success or failure alike, counts as "ready" to show.
fun UiState<*>.isReady(): Boolean = this !is UiState.Loading
