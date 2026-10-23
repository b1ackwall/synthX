package com.v1per.synthx.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Home has no state yet. The ViewModel keeps the one-ViewModel-per-screen wiring and
 * takes a Factory dependency when one arrives.
 */
class HomeViewModel : ViewModel() {

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            // Class<T> is erased; this factory only ever builds HomeViewModel.
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel() as T
        }
    }
}
