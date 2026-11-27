package com.v1per.synthx.data.scanner

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException

private const val TAG = "ReplacementReadability"

/** How much of the replacement's file the probe reads. A prefix, not the whole file. */
internal const val REPLACEMENT_PROBE_BYTES = 4 * 1024

/**
 * Whether the document at [filePath] can actually be opened and read: opens it through the
 * content resolver and reads up to [REPLACEMENT_PROBE_BYTES] from the start. True only when at
 * least one byte came back. Uses no provider metadata (existence, size or flags), so a document
 * that the provider lists but cannot serve reads as unreadable.
 */
internal fun readsAudioFilePrefix(context: Context, filePath: String): Boolean = try {
    val uri = filePath.toUri()
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(REPLACEMENT_PROBE_BYTES)
        input.read(buffer, 0, buffer.size) > 0
    } ?: false
} catch (e: Exception) {
    if (e is CancellationException) throw e
    // The class name only: a provider's exception message can carry the document's name.
    Log.w(TAG, "Replacement file could not be opened or read: ${e.javaClass.simpleName}")
    false
}
