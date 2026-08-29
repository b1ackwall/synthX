package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TAG = "SynthXScan"

private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg")

/**
 * Reachability precondition for a configured SAF root: is [uri] still granted, and does it
 * resolve to a real directory right now? Returns the resolved [DocumentFile] on success, or
 * `null` if either check fails.
 *
 * Reachability is probed explicitly rather than inferred from a walk, because DocumentFile
 * swallows every provider error: `listFiles()` catches its query exception and returns an
 * empty array, and the metadata accessors return their default on failure. A revoked grant,
 * an ejected volume, or a transient provider hiccup are therefore indistinguishable from a
 * genuinely empty folder at the walk level, and trusting that silence is exactly how every
 * song under the root gets flagged missing.
 */
internal fun isRootReachable(context: Context, uri: Uri): DocumentFile? {
    // The definitive revocation signal — a stored URI outlives its grant.
    val hasReadGrant = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }
    if (!hasReadGrant) return null

    val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    // isDirectory is a real query, so it also catches a grant that exists but no longer
    // resolves (deleted folder, ejected volume, provider error).
    return root?.takeIf { it.isDirectory }
}

/**
 * Enumerates every [otherRootUri][otherRootUris] into [into], and returns whether
 * missing-detection may proceed.
 *
 * Returns `false` — abort reconciliation — the moment any root can't be enumerated,
 * without partially populating a decision the caller would then act on.
 */
internal suspend fun collectOtherRootPaths(
    context: Context,
    otherRootUris: List<Uri>,
    into: MutableSet<String>,
): Boolean {
    for (uri in otherRootUris) {
        currentCoroutineContext().ensureActive()

        val otherRoot = isRootReachable(context, uri)
        if (otherRoot == null) {
            Log.w(TAG, "Skipping missing-detection: configured root is unreachable — $uri")
            return false
        }

        try {
            collectAudioFiles(otherRoot).mapTo(into) { it.uri.toString() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Skipping missing-detection: failed to walk configured root $uri", e)
            return false
        }
    }
    return true
}

/**
 * Pure enumeration — no hashing, no metadata, no DB access. Suspending only for the
 * cancellation check: this now also runs against secondary roots before the transaction
 * opens, where the per-file `ensureActive` in the ingest loop offers no cover, and a
 * deep tree is thousands of binder round-trips with nothing else to interrupt it.
 */
internal suspend fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
    currentCoroutineContext().ensureActive()
    val result = mutableListOf<DocumentFile>()
    for (child in dir.listFiles()) {
        when {
            child.isDirectory -> result.addAll(collectAudioFiles(child))
            child.name?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_EXTENSIONS ->
                result.add(child)
        }
    }
    return result
}
