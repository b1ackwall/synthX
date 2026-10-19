package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
 * [isRootReachable]'s write-capable counterpart: same resolution, but the grant must include
 * write, not merely read. Returns the resolved [DocumentFile] on success, `null` otherwise.
 *
 * A separate function rather than a flag on [isRootReachable] because the two answer genuinely
 * different questions and almost every caller wants the read one — scanning and missing-detection
 * only ever read, and must keep working against a read-only grant. Graduation uses this before
 * creating a file, and [DestructiveFilePreflight] uses it before deleting one.
 *
 * Mirrors [com.v1per.synthx.data.backup.BackupFolderHealth]'s grant check, which makes the
 * same read-vs-write distinction for the backup tree. It stops short of
 * that class's second step — probing writability by actually creating something — because
 * graduation's very next act *is* the real write, and its failure is already reported distinctly;
 * a speculative probe here would only duplicate it.
 *
 * A `null` here does not mean the root is unreachable. Callers that want to tell "no write grant"
 * apart from "gone entirely" should fall back to [isRootReachable] to distinguish the two — the
 * difference is the whole reason this exists, since only the former is fixable by the user
 * re-picking the folder.
 */
internal fun isRootWritable(context: Context, uri: Uri): DocumentFile? {
    val hasWriteGrant = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isWritePermission
    }
    if (!hasWriteGrant) return null

    val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    return root?.takeIf { it.isDirectory }
}

/**
 * Enumerates every [otherRootUri][otherRootUris] into [into], preserving files found before an
 * uncertain directory query, and returns every root-scoped degradation encountered.
 *
 * Any degradation tells the caller to abort absence-based reconciliation. Successfully observed
 * paths remain useful evidence of presence even when the complete tree could not be enumerated.
 */
internal suspend fun collectOtherRootPaths(
    context: Context,
    otherRootUris: List<Uri>,
    into: MutableSet<String>,
    root: KnownAudioRoot,
    rootResolver: (Uri) -> DocumentFile? = { isRootReachable(context, it) },
    directoryQuery: DirectoryChildrenQuery = AndroidDirectoryChildrenQuery(context),
): Set<ScanDegradation> {
    val degradations = linkedSetOf<ScanDegradation>()
    for (uri in otherRootUris) {
        currentCoroutineContext().ensureActive()

        val otherRoot = rootResolver(uri)
        if (otherRoot == null) {
            Log.w(TAG, "Skipping missing-detection: configured root is unreachable — $uri")
            degradations += ScanDegradation(root, ScanDegradationReason.RootUnreachable)
            continue
        }

        try {
            val walk = collectAudioFiles(otherRoot, directoryQuery)
            walk.files.mapTo(into) { it.uri.toString() }
            if (!walk.complete) {
                Log.w(TAG, "Skipping missing-detection: configured root walk was incomplete — $uri")
                degradations += ScanDegradation(root, ScanDegradationReason.WalkIncomplete)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Skipping missing-detection: failed to walk configured root $uri", e)
            degradations += ScanDegradation(root, ScanDegradationReason.WalkIncomplete)
        }
    }
    return degradations
}

internal data class DirectoryChild(
    val document: DocumentFile,
    val name: String?,
    val isDirectory: Boolean,
)

/** A successful prefix may be returned with [complete] false if iteration failed part-way. */
internal data class DirectoryListing(
    val children: List<DirectoryChild>,
    val complete: Boolean,
)

internal fun interface DirectoryChildrenQuery {
    fun list(directory: DocumentFile): DirectoryListing
}

/**
 * Direct SAF child query. Unlike DocumentFile.listFiles(), failures remain observable instead of
 * being converted to a trustworthy-looking empty array. Name and type come from the same cursor,
 * avoiding the metadata accessors' equivalent silent-default behaviour.
 */
internal class AndroidDirectoryChildrenQuery(private val context: Context) : DirectoryChildrenQuery {
    override fun list(directory: DocumentFile): DirectoryListing {
        val children = mutableListOf<DirectoryChild>()
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                directory.uri,
                DocumentsContract.getDocumentId(directory.uri),
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return DirectoryListing(children, complete = false)
        }

        val cursor = try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            ) ?: return DirectoryListing(children, complete = false)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return DirectoryListing(children, complete = false)
        }

        var complete = true
        try {
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idColumn < 0 || nameColumn < 0 || typeColumn < 0) {
                complete = false
            } else {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idColumn)
                    val mimeType = cursor.getString(typeColumn)
                    val name = cursor.getString(nameColumn)
                    if (documentId == null || mimeType == null ||
                        (mimeType != DocumentsContract.Document.MIME_TYPE_DIR && name == null)
                    ) {
                        complete = false
                        continue
                    }
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(directory.uri, documentId)
                    val child = DocumentFile.fromSingleUri(context, childUri)
                    if (child == null) {
                        complete = false
                        continue
                    }
                    children += DirectoryChild(
                        document = child,
                        name = name,
                        isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
                val extras = cursor.extras
                if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                    extras.containsKey(DocumentsContract.EXTRA_ERROR)
                ) {
                    complete = false
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            complete = false
        } finally {
            try {
                cursor.close()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                complete = false
            }
        }
        return DirectoryListing(children, complete)
    }
}

internal data class AudioWalkResult(
    val files: List<DocumentFile>,
    val complete: Boolean,
)

/**
 * Pure enumeration — no hashing, no metadata, no DB access. Suspending only for the
 * cancellation check: this now also runs against secondary roots before the transaction
 * opens, where the per-file `ensureActive` in the ingest loop offers no cover, and a
 * deep tree is thousands of binder round-trips with nothing else to interrupt it.
 */
internal suspend fun collectAudioFiles(
    dir: DocumentFile,
    directoryQuery: DirectoryChildrenQuery,
): AudioWalkResult {
    currentCoroutineContext().ensureActive()
    val result = mutableListOf<DocumentFile>()
    val listing = directoryQuery.list(dir)
    var complete = listing.complete
    for (child in listing.children) {
        if (child.isDirectory) {
            val nested = collectAudioFiles(child.document, directoryQuery)
            result += nested.files
            complete = complete && nested.complete
        } else if (isScannableAudioName(child.name)) {
            result += child.document
        }
    }
    return AudioWalkResult(result, complete)
}

internal suspend fun collectAudioFiles(context: Context, dir: DocumentFile): AudioWalkResult =
    collectAudioFiles(dir, AndroidDirectoryChildrenQuery(context))

/**
 * Whether a file named [name] is an audio file a scan should ingest.
 *
 * Graduation's in-progress copy ([GRADUATION_TEMP_PREFIX]) is excluded outright even though it
 * keeps the real extension: a partial copy would hash to a brand-new id and insert a junk row, and
 * a complete-but-unpublished one hashes to the real song's id and would repoint that song at a
 * temp URI that dies on rename. Independent of [com.v1per.synthx.data.FileOperationCoordinator]'s
 * locking — a file that is never enumerated can't corrupt anything downstream, whatever gap the
 * locking might have.
 */
internal fun isScannableAudioName(name: String?): Boolean {
    if (name == null || name.startsWith(GRADUATION_TEMP_PREFIX)) return false
    return name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
}

/** Name prefix of graduation's temporary destination file — see [graduateSong]. */
internal const val GRADUATION_TEMP_PREFIX = ".graduating_"
