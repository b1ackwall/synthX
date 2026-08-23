package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decides whether two SAF tree roots overlap.
 *
 * Two configured audio roots must be disjoint subtrees. If one contains the other, a single
 * physical file is reachable from both walks — and because a document URI is built as
 * `content://{authority}/tree/{treeDocumentId}/document/{documentId}`, with the `tree/`
 * segment inherited from whichever root the walk started at, the same file yields *two
 * different* `filePath` strings. That makes each root's scan see the other's rows as a
 * path change and rewrite them, so the song churns as "updated" on every alternating scan,
 * and no path-set union can reconcile the two forms.
 *
 * Detection is layered, cheapest and most certain first, because document ids are
 * provider-defined and have no guaranteed structure:
 *
 *  1. Different authorities can't nest — different providers entirely.
 *  2. Identical tree document ids are the same folder.
 *  3. A `parentId/childId` prefix match. Sound for the built-in ExternalStorageProvider and
 *     DownloadsProvider, whose ids are path-like (`primary:Music/Downloads`), and the reason
 *     the `/` boundary is required — `primary:Music` must not be read as containing
 *     `primary:MusicVideos`. Positive matches only; a miss proves nothing here.
 *  4. [DocumentsContract.isChildDocument], which asks the provider itself and answers for
 *     descendants at any depth, not just direct children. Authoritative where supported, but
 *     a provider that doesn't implement it returns false rather than saying so, which is why
 *     it runs last rather than alone.
 */
internal object RootNesting {

    private const val TAG = "SynthXScan"

    enum class Relation {
        UNRELATED,
        SAME,
        /** The first URI passed to [detect] contains the second. */
        FIRST_CONTAINS_SECOND,
        /** The second URI passed to [detect] contains the first. */
        SECOND_CONTAINS_FIRST;

        val isConflict: Boolean get() = this != UNRELATED
    }

    /**
     * Binder round-trips in step 4, so this suspends and runs on IO rather than being called
     * straight from a picker callback on the main thread.
     */
    suspend fun detect(context: Context, first: Uri, second: Uri): Relation =
        withContext(Dispatchers.IO) {
            if (first.authority != second.authority) return@withContext Relation.UNRELATED

            val firstId = treeDocumentId(first) ?: return@withContext Relation.UNRELATED
            val secondId = treeDocumentId(second) ?: return@withContext Relation.UNRELATED
            if (firstId == secondId) return@withContext Relation.SAME

            if (isPathPrefix(firstId, secondId)) return@withContext Relation.FIRST_CONTAINS_SECOND
            if (isPathPrefix(secondId, firstId)) return@withContext Relation.SECOND_CONTAINS_FIRST

            val firstDoc = treeDocumentUri(first) ?: return@withContext Relation.UNRELATED
            val secondDoc = treeDocumentUri(second) ?: return@withContext Relation.UNRELATED
            when {
                isDescendant(context, parent = firstDoc, child = secondDoc) ->
                    Relation.FIRST_CONTAINS_SECOND
                isDescendant(context, parent = secondDoc, child = firstDoc) ->
                    Relation.SECOND_CONTAINS_FIRST
                else -> Relation.UNRELATED
            }
        }

    /**
     * Path-like containment. A volume-root id ends in ':' and has no separator of its own
     * (`primary:` contains `primary:Music`), so it's handled without appending one.
     */
    private fun isPathPrefix(parentId: String, childId: String): Boolean =
        if (parentId.endsWith(":")) childId.startsWith(parentId) else childId.startsWith("$parentId/")

    private fun isDescendant(context: Context, parent: Uri, child: Uri): Boolean =
        runCatching { DocumentsContract.isChildDocument(context.contentResolver, parent, child) }
            .onFailure { Log.w(TAG, "isChildDocument probe failed for $parent: ${it.message}") }
            .getOrDefault(false)

    private fun treeDocumentId(uri: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

    /** The `/tree/X/document/X` form of a picked tree root, which is what the walk produces. */
    private fun treeDocumentUri(uri: Uri): Uri? = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()
}
