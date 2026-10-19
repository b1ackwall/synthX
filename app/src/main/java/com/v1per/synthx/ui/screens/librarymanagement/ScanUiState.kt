package com.v1per.synthx.ui.screens.librarymanagement

import androidx.work.WorkInfo
import com.v1per.synthx.ui.common.ScanStatusStrings
import com.v1per.synthx.ui.common.scanStatusFor

/** Determinate when [total] is positive; otherwise the scan is in an uncounted phase. */
data class ScanProgress(val current: Int, val total: Int) {
    val isDeterminate: Boolean get() = total > 0
    val fraction: Float get() = if (isDeterminate) current.toFloat() / total else 0f
}

/** ScanningStep's view of the first scan — see [LibraryManagementViewModel.firstScanState]. */
sealed interface FirstScanState {
    /** [status] is the live progress line, or null before the first WorkInfo is observed. */
    data class Running(val status: String?) : FirstScanState

    data object Succeeded : FirstScanState

    /** [cancelled] distinguishes a cancelled scan from a failed one (same recovery, own wording). */
    data class Failed(val message: String, val cancelled: Boolean) : FirstScanState
}

/** Branches on the actual terminal [WorkInfo.State] — never on a status string existing. */
internal fun firstScanStateFor(info: WorkInfo, strings: ScanStatusStrings): FirstScanState =
    when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING ->
            FirstScanState.Running(scanStatusFor(info, strings))
        WorkInfo.State.SUCCEEDED -> FirstScanState.Succeeded
        WorkInfo.State.FAILED ->
            FirstScanState.Failed(scanStatusFor(info, strings).orEmpty(), cancelled = false)
        WorkInfo.State.CANCELLED ->
            FirstScanState.Failed(scanStatusFor(info, strings).orEmpty(), cancelled = true)
    }

/**
 * Whether a New Arrivals scan that was queued behind another scan is still waiting to start
 * Once it is running or finished, "will be scanned once it finishes" is stale:
 * running means it is no longer deferred, and finished (or failed, or cancelled) means the
 * promise has been kept or definitively broken.
 */
internal fun isDeferredScanPending(state: WorkInfo.State): Boolean =
    state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
