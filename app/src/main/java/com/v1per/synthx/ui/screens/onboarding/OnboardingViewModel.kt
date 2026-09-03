package com.v1per.synthx.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Internal steps of the wizard shown when [com.v1per.synthx.preferences.PreferencesManager.musicFolderUri]
 * is null — see [OnboardingScreen]. Modeled as state within one screen/route rather than
 * separate nav routes: the flow is short, linear, and one-time, so there is nothing here that
 * needs its own back-stack entry or deep-linkability.
 */
enum class OnboardingStep { Choice, FolderSetup, Scanning }

/**
 * Owns only the wizard's step state. The actual folder-picking, validation, persistence and
 * first-scan trigger are deliberately NOT reimplemented here — [OnboardingScreen] obtains
 * [com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel] and
 * [com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel] directly and calls
 * their existing saveMusicFolder/saveNewArrivalsFolder/saveBackupFolder methods, so this stays a
 * thin step-sequencer rather than a second copy of that logic.
 */
class OnboardingViewModel : ViewModel() {

    private val _step = MutableStateFlow(OnboardingStep.Choice)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    fun goToFolderSetup() {
        _step.value = OnboardingStep.FolderSetup
    }

    fun goToChoice() {
        _step.value = OnboardingStep.Choice
    }

    fun goToScanning() {
        _step.value = OnboardingStep.Scanning
    }
}
