package com.v1per.synthx.ui.screens.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Internal steps of the wizard shown when [com.v1per.synthx.preferences.PreferencesManager.onboardingComplete]
 * is false — see [OnboardingScreen]. Modeled as state within one screen/route rather than
 * separate nav routes: the flow is short, linear, and one-time, so there is nothing here that
 * needs its own back-stack entry or deep-linkability.
 */
enum class OnboardingStep { Choice, FolderSetup, Scanning, RecoveryFolderSetup, RecoveryBackupSelect }

/**
 * Owns only the wizard's step state. The actual folder-picking, validation, persistence and
 * first-scan trigger are deliberately NOT reimplemented here — [OnboardingScreen] obtains
 * [com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel] and
 * [com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreViewModel] directly and calls
 * their folder methods. Recovery uses separate audio-folder saves without scan/backup side
 * effects, so this stays a thin step-sequencer rather than a second copy of that logic.
 *
 * Position is persisted through [preferences] (P7-T2 / R7-F13) rather than a `SavedStateHandle`:
 * this ViewModel is nav-backstack-scoped, so a `SavedStateHandle` would survive configuration
 * changes and normal process death, but NOT a user swiping the app away from Recents — one of
 * the two loss scenarios the finding explicitly calls out. Disk-backed state survives both.
 */
class OnboardingViewModel(
    private val preferences: PreferencesManager,
) : ViewModel() {

    private val resumedStep = preferences.onboardingStepName
        ?.let { name -> runCatching { OnboardingStep.valueOf(name) }.getOrNull() }

    private val _step = MutableStateFlow(resumedStep ?: OnboardingStep.Choice)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    /**
     * True only when this instance actually resumed a persisted, non-[OnboardingStep.Choice]
     * position — i.e. process death or a swipe-away genuinely interrupted a wizard already in
     * progress. Drives [OnboardingScreen]'s lightweight "Resuming recovery" notice; resuming at
     * Choice itself is indistinguishable from a fresh start, so that case is excluded.
     */
    val resumedFromPersistedStep: Boolean =
        resumedStep != null && resumedStep != OnboardingStep.Choice

    var returningFromRecovery: Boolean = preferences.onboardingReturningFromRecovery
        private set

    private fun moveTo(step: OnboardingStep) {
        _step.value = step
        check(preferences.setOnboardingProgressDurably(step.name, returningFromRecovery)) {
            "Could not persist onboarding wizard position"
        }
    }

    fun goToFolderSetup() {
        returningFromRecovery = false
        moveTo(OnboardingStep.FolderSetup)
    }

    fun goToChoice() {
        moveTo(OnboardingStep.Choice)
    }

    fun goToScanning() {
        moveTo(OnboardingStep.Scanning)
    }

    fun goToRecoveryFolderSetup() {
        returningFromRecovery = true
        moveTo(OnboardingStep.RecoveryFolderSetup)
    }

    fun goToRecoveryBackupSelect() {
        moveTo(OnboardingStep.RecoveryBackupSelect)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OnboardingViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return OnboardingViewModel(PreferencesManager(context.applicationContext)) as T
        }
    }
}
