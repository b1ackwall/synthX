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

    /**
     * Authorities whose tree document ids are known to be path-like (per the class doc) and
     * whose [DocumentsContract.isChildDocument] implementation can be trusted to answer
     * `false` only when the roots are genuinely unrelated. Used solely by [detect] to decide
     * whether a negative isChildDocument result means "confirmed unrelated" or "undetermined."
     */
    private val PATH_LIKE_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
    )

    private fun isKnownPathLikeAuthority(authority: String?): Boolean = authority in PATH_LIKE_AUTHORITIES

    enum class Relation {
        UNRELATED,
        SAME,

        /** The first URI passed to [detect] contains the second. */
        FIRST_CONTAINS_SECOND,

        /** The second URI passed to [detect] contains the first. */
        SECOND_CONTAINS_FIRST,

        /**
         * Nesting could not be conclusively ruled out. Reached only when the tree document
         * ids aren't path-like (so [isPathPrefix] can't apply) *and* the authority isn't one
         * of the built-ins ([PATH_LIKE_AUTHORITIES]) known to implement
         * [DocumentsContract.isChildDocument] faithfully — for any other provider, a `false`
         * from that call means either "confirmed not nested" or "this provider doesn't
         * implement the check," and the two are indistinguishable from the API alone (see
         * class doc). Distinct from [UNRELATED] specifically so callers can fail closed on
         * "can't tell" instead of silently treating it as "confirmed safe."
         */
        UNDETERMINED,

        ;

        val isConflict: Boolean get() = this != UNRELATED
    }

    /**
     * Binder round-trips in step 4, so this suspends and runs on IO rather than being called
     * straight from a picker callback on the main thread.
     */
    suspend fun detect(context: Context, first: Uri, second: Uri): Relation = withContext(Dispatchers.IO) {
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

            // A negative isChildDocument result is only trustworthy for the providers
            // known to implement it faithfully. For anything else — opaque, non-path-like
            // document ids — a `false` here doesn't prove the roots are unrelated, so
            // don't report a confirmed UNRELATED; report that nesting couldn't be
            // determined instead, and let callers fail closed on it.
            isKnownPathLikeAuthority(first.authority) -> Relation.UNRELATED

            else -> Relation.UNDETERMINED
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

    private fun treeDocumentId(uri: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

    /** The `/tree/X/document/X` form of a picked tree root, which is what the walk produces. */
    private fun treeDocumentUri(uri: Uri): Uri? = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()

    /**
     * Whether [documentUri] — a stored song `file_path`, itself a `/tree/X/document/Y` URI
     * produced by a walk under *some* configured root — falls under [rootUri]. Same layered
     * approach as [detect], cheapest and most certain first, reused here to map a song back
     * to the root that owns it (e.g. so a launch-time verify pass can skip songs under a
     * root that failed its reachability check rather than probing them individually).
     */
    suspend fun isUnderRoot(context: Context, documentUri: Uri, rootUri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (documentUri.authority != rootUri.authority) return@withContext false

        val rootTreeId = treeDocumentId(rootUri) ?: return@withContext false
        val songDocId = runCatching { DocumentsContract.getDocumentId(documentUri) }
            .getOrNull() ?: return@withContext false

        if (songDocId == rootTreeId || isPathPrefix(rootTreeId, songDocId)) {
            return@withContext true
        }

        val rootDoc = treeDocumentUri(rootUri) ?: return@withContext false
        isDescendant(context, parent = rootDoc, child = documentUri)
    }
}
