package com.v1per.synthx.ui.common

import androidx.work.WorkInfo
import com.v1per.synthx.R
import com.v1per.synthx.data.scanner.KnownAudioRoot
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.data.scanner.ScanDegradation
import com.v1per.synthx.data.scanner.ScanDegradationReason
import com.v1per.synthx.data.scanner.VerifyLibraryWorker
import com.v1per.synthx.data.scanner.scanDegradations

/*
 * Pure, Context-free scan-status text shared by LibraryManagementViewModel (Settings rescan
 * row, onboarding first scan) and LibraryViewModel (pull-to-refresh). Extracted so neither
 * ViewModel depends on the other — string lookup is injected as [ScanStatusStrings] rather than
 * reaching for a Context, which is also what makes every branch JVM-unit-testable.
 */

/** WorkManager tags every request with its worker's fully-qualified class name. */
internal val LIBRARY_SCAN_TAG: String = LibraryScanWorker::class.java.name
internal val NEW_ARRIVALS_TAG: String = NewArrivalsScanWorker::class.java.name
internal val VERIFY_TAG: String = VerifyLibraryWorker::class.java.name

/** Resolves a string resource with format args — `context.getString(id, *args)` in production. */
fun interface ScanStatusStrings {
    fun get(id: Int, vararg args: Any): String
}

/** Preparing/running label for whichever worker [info] belongs to. */
private fun preparingLabelFor(info: WorkInfo, strings: ScanStatusStrings): String = when {
    LIBRARY_SCAN_TAG in info.tags -> strings.get(R.string.scan_progress_library)
    NEW_ARRIVALS_TAG in info.tags -> strings.get(R.string.scan_progress_new_arrivals)
    VERIFY_TAG in info.tags -> strings.get(R.string.scan_progress_verify)
    else -> strings.get(R.string.scan_progress_preparing)
}

private fun degradationMessage(
    degradations: Set<ScanDegradation>,
    strings: ScanStatusStrings,
): String? {
    if (degradations.isEmpty()) return null
    return degradations.joinToString(" ") { degradation ->
        val rootName = strings.get(
            when (degradation.root) {
                KnownAudioRoot.Music -> R.string.scan_root_music
                KnownAudioRoot.NewArrivals -> R.string.scan_root_new_arrivals
            },
        )
        strings.get(
            when (degradation.reason) {
                ScanDegradationReason.RootUnreachable -> R.string.scan_degraded_root_unreachable
                ScanDegradationReason.WalkIncomplete -> R.string.scan_degraded_walk_incomplete
                ScanDegradationReason.VerificationIncomplete ->
                    R.string.scan_degraded_verification_incomplete
            },
            rootName,
        )
    }
}

/**
 * The status line for one scan-family WorkInfo, or null when there is nothing to say (no work,
 * or a SUCCEEDED run from a worker this helper doesn't know).
 *
 * FAILED and CANCELLED deliberately produce *text* — callers that must treat them differently
 * from success (onboarding's ScanningStep) branch on [WorkInfo.State], not on this string being
 * non-null: treating any status line as success would let a failed scan complete onboarding.
 */
fun scanStatusFor(info: WorkInfo?, strings: ScanStatusStrings): String? = when (info?.state) {
    null -> null
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> preparingLabelFor(info, strings)
    WorkInfo.State.RUNNING -> when {
        LIBRARY_SCAN_TAG in info.tags -> {
            val current = info.progress.getInt(LibraryScanWorker.KEY_CURRENT, -1)
            val total = info.progress.getInt(LibraryScanWorker.KEY_TOTAL, -1)
            if (total > 0) {
                strings.get(R.string.scan_progress_counted, current, total)
            } else {
                preparingLabelFor(info, strings)
            }
        }
        else -> preparingLabelFor(info, strings)
    }
    WorkInfo.State.SUCCEEDED -> when {
        LIBRARY_SCAN_TAG in info.tags -> {
            val data = info.outputData
            val warning = degradationMessage(data.scanDegradations(), strings)
            if (warning != null) {
                strings.get(
                    R.string.scan_status_degraded_counts,
                    data.getInt(LibraryScanWorker.KEY_ADDED, 0),
                    data.getInt(LibraryScanWorker.KEY_UPDATED, 0),
                    warning,
                )
            } else {
                val doneMessage = strings.get(
                    R.string.scan_status_done,
                    data.getInt(LibraryScanWorker.KEY_ADDED, 0),
                    data.getInt(LibraryScanWorker.KEY_UPDATED, 0),
                    data.getInt(LibraryScanWorker.KEY_MISSING, 0),
                )
                if (data.getBoolean(LibraryScanWorker.KEY_HIGH_IMPACT, false)) {
                    val highImpactMessage = strings.get(
                        R.string.scan_status_high_impact,
                        data.getInt(LibraryScanWorker.KEY_IMPACT_COUNT, 0),
                        data.getString(LibraryScanWorker.KEY_BACKUP_FILENAME)
                            ?: strings.get(R.string.scan_status_backup_filename_unknown),
                    )
                    "$doneMessage $highImpactMessage"
                } else {
                    doneMessage
                }
            }
        }
        NEW_ARRIVALS_TAG in info.tags -> {
            val added = info.outputData.getInt(LibraryScanWorker.KEY_ADDED, 0)
            val warning = degradationMessage(info.outputData.scanDegradations(), strings)
            when {
                warning != null -> strings.get(R.string.scan_status_new_arrivals_degraded, added, warning)
                added > 0 -> strings.get(R.string.scan_status_new_arrivals_found, added)
                else -> strings.get(R.string.scan_status_new_arrivals_none)
            }
        }
        VERIFY_TAG in info.tags -> {
            val newlyMissing = info.outputData.getInt(VerifyLibraryWorker.KEY_NEWLY_MISSING, 0)
            val warning = degradationMessage(info.outputData.scanDegradations(), strings)
            when {
                warning != null -> strings.get(R.string.scan_status_verify_degraded, newlyMissing, warning)
                newlyMissing > 0 -> strings.get(R.string.scan_status_verify_missing, newlyMissing)
                else -> strings.get(R.string.scan_status_verify_none)
            }
        }
        else -> null
    }
    WorkInfo.State.FAILED -> strings.get(
        R.string.scan_status_failed,
        info.outputData.getString(LibraryScanWorker.KEY_ERROR)
            ?: strings.get(R.string.scan_status_unknown_error),
    )
    WorkInfo.State.CANCELLED -> strings.get(R.string.scan_cancelled)
}
