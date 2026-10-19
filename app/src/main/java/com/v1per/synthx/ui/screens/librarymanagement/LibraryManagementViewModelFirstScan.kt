package com.v1per.synthx.ui.screens.librarymanagement

import androidx.work.ExistingWorkPolicy
import com.v1per.synthx.R
import com.v1per.synthx.data.scanner.LibraryScanWorker
import java.util.UUID

// LibraryManagementViewModel's onboarding first-scan bookkeeping.

/**
 * Idempotent no-op once onboarding is already complete. Only [startFirstScan] calls this:
 * completion happens at Done, not at the folder pick, so that a first scan the user has not
 * yet confirmed can never be the thing that flips the flag.
 */
private fun LibraryManagementViewModel.markOnboardingCompleteIfNeeded() {
    if (!preferencesManager.onboardingComplete) {
        check(preferencesManager.setOnboardingCompleteDurably(true)) {
            "Could not persist onboarding completion"
        }
    }
}

internal fun LibraryManagementViewModel.startFirstScanInternal(): UUID? {
    if (preferencesManager.musicFolderUri == null) {
        _folderError.showTemporarily(context.getString(R.string.settings_no_folder_selected))
        return null
    }
    return try {
        markOnboardingCompleteIfNeeded()
        val id = LibraryScanWorker.enqueue(context, ExistingWorkPolicy.REPLACE).id
        check(preferencesManager.trackFirstScanRequestDurably(id.toString())) {
            "Could not persist first-scan request id"
        }
        _localStatus.value = null
        _firstScanRequestId.value = id
        id
    } catch (e: Exception) {
        _folderError.showTemporarily(context.getString(R.string.error_folder_save_failed))
        null
    }
}

internal fun LibraryManagementViewModel.completeFirstScanInternal() {
    check(preferencesManager.completeFirstScanDurably()) {
        "Could not persist first-scan completion"
    }
}

internal fun LibraryManagementViewModel.abandonFirstScanInternal() {
    check(preferencesManager.abandonFirstScanDurably()) {
        "Could not persist first-scan abandonment"
    }
    _firstScanRequestId.value = null
}
