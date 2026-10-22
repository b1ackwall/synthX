package com.v1per.synthx.data.scanner

import android.net.Uri
import androidx.work.Data

enum class ScanDegradationReason {
    RootUnreachable,
    WalkIncomplete,
    VerificationIncomplete,
}

data class ScanDegradation(val root: KnownAudioRoot, val reason: ScanDegradationReason)

data class ConfiguredAudioRoot(val uri: Uri, val role: KnownAudioRoot)

data class VerifyResult(val newlyMissing: Int, val degradations: Set<ScanDegradation> = emptySet())

internal const val KEY_SCAN_DEGRADATIONS = "scan_degradations"

internal fun Data.Builder.putScanDegradations(degradations: Collection<ScanDegradation>): Data.Builder = apply {
    if (degradations.isNotEmpty()) {
        putStringArray(
            KEY_SCAN_DEGRADATIONS,
            degradations.map { "${it.root.name}:${it.reason.name}" }.toTypedArray(),
        )
    }
}

internal fun Data.scanDegradations(): Set<ScanDegradation> =
    getNullableStringArray(KEY_SCAN_DEGRADATIONS).orEmpty().filterNotNull().mapNotNullTo(linkedSetOf()) { encoded ->
        val parts = encoded.split(':', limit = 2)
        if (parts.size != 2) return@mapNotNullTo null
        val root = runCatching { KnownAudioRoot.valueOf(parts[0]) }.getOrNull()
            ?: return@mapNotNullTo null
        val reason = runCatching { ScanDegradationReason.valueOf(parts[1]) }.getOrNull()
            ?: return@mapNotNullTo null
        ScanDegradation(root, reason)
    }

internal fun scanOutputData(result: ScanResult, includeMissing: Boolean = true): Data = Data.Builder()
    .putInt(LibraryScanWorker.KEY_ADDED, result.added)
    .putInt(LibraryScanWorker.KEY_UPDATED, result.updated)
    .putInt(LibraryScanWorker.KEY_UNCHANGED, result.unchanged)
    .apply {
        if (includeMissing) putInt(LibraryScanWorker.KEY_MISSING, result.missing)
    }
    .putScanDegradations(result.degradations)
    .build()

internal fun verifyOutputData(result: VerifyResult): Data = Data.Builder()
    .putInt(VerifyLibraryWorker.KEY_NEWLY_MISSING, result.newlyMissing)
    .putScanDegradations(result.degradations)
    .build()
