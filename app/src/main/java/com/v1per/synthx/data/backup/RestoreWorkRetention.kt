package com.v1per.synthx.data.backup

import androidx.work.WorkManager
import androidx.work.Operation
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-local invalidation paired with the durable preference write. */
internal object RestoreRequestTracking {
    private val _revision = MutableStateFlow(0L)
    val revision = _revision
    fun changed() { _revision.value += 1L }
}

/**
 * WorkManager only offers database-wide pruning. Preserve all finished records while the tracked
 * restore still has a terminal result the root observer has not actually shown.
 */
internal suspend fun pruneFinishedWorkIfRestoreAcknowledged(
    workManager: WorkManager,
    preferences: PreferencesManager,
): Operation? {
    if (preferences.restoreRequestId != null && !preferences.restoreOutcomeAcknowledged) return null
    // P9-T1: a fresh-install first scan the wizard has not yet accepted or abandoned still owns its
    // terminal WorkInfo. Pruning it would leave the resumed ScanningStep with an id that resolves
    // to nothing — indistinguishable from a scan that never ran.
    if (preferences.firstScanRequestId != null) return null
    if (preferences.restoreRequestId == null) {
        val restoreHistory = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).get()
        }
        if (restoreHistory.isNotEmpty()) return null
    }
    return workManager.pruneWork()
}
