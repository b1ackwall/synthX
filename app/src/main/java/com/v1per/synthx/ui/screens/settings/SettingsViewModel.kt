package com.v1per.synthx.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Settings is a menu screen — the folder picker and rescan live in
 * LibraryManagementViewModel. Kept as a shell (one ViewModel per screen) so the next
 * setting added here has a home.
 *
 * The Factory takes no Context because the shell has no dependencies to build; add the
 * `Factory(private val context: Context)` parameter back alongside the first one that does.
 */
class SettingsViewModel : ViewModel() {

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel() as T
        }
    }
}
