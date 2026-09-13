package com.v1per.synthx.data.scanner

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException

/** A configured audio tree whose name is meaningful to the user. */
enum class KnownAudioRoot { Music, NewArrivals }

/** Result of validating an audio document immediately before deleting it. */
sealed interface DestructiveFilePreflightResult {
    data object Passed : DestructiveFilePreflightResult
    data object SourceGone : DestructiveFilePreflightResult
    data object Ambiguous : DestructiveFilePreflightResult

    sealed interface Blocking : DestructiveFilePreflightResult
    data class GrantNotWritable(val root: KnownAudioRoot) : Blocking
    data object DocumentNotWritable : Blocking
    data object IdentityMismatch : Blocking
    data object RootUnknown : Blocking
}

/** Result of validating the replacement song, which is read but never deleted. */
sealed interface ReplacementFilePreflightResult {
    data object Passed : ReplacementFilePreflightResult
    data object Ambiguous : ReplacementFilePreflightResult

    sealed interface Blocking : ReplacementFilePreflightResult
    data object SourceGone : Blocking
    data object IdentityMismatch : Blocking
}

internal data class ConfiguredAudioRoots(
    val music: String?,
    val newArrivals: String?,
)

internal sealed interface SourceDocumentResolution {
    data class Present(val uri: String) : SourceDocumentResolution
    data object Gone : SourceDocumentResolution
    data object Ambiguous : SourceDocumentResolution
}

internal enum class RootWriteState { Writable, GrantNotWritable, Ambiguous }

/**
 * The SAF boundary used by [DestructiveFilePreflight]. Kept as a narrow seam because local JVM
 * tests cannot obtain real persisted tree grants or install a DocumentsProvider through the system
 * picker. Production still runs every operation below against ContentResolver/DocumentFile.
 */
internal interface AudioDocumentPreflightProbe {
    fun resolve(filePath: String): SourceDocumentResolution
    suspend fun isUnderRoot(documentUri: String, rootUri: String): Boolean
    fun rootWriteState(rootUri: String): RootWriteState
    fun canWrite(documentUri: String): Boolean?
    fun computeSongId(documentUri: String): String?
}

/**
 * Live SAF preflight for destructive song operations. The caller is responsible for holding the
 * affected song's [com.v1per.synthx.data.FileOperationCoordinator] lock across this check and the
 * subsequent commit.
 *
 * Only facts established with certainty block an operation. An inconclusive provider query or
 * hash falls through as [DestructiveFilePreflightResult.Ambiguous], preserving the existing
 * post-commit cleanup reporting. A confirmed identity, permission, ownership, or document-level
 * refusal is returned distinctly before any database transaction starts.
 */
class DestructiveFilePreflight internal constructor(
    private val probe: AudioDocumentPreflightProbe,
    private val configuredRoots: () -> ConfiguredAudioRoots,
) {
    suspend fun checkDestructive(
        filePath: String,
        expectedSongId: String,
    ): DestructiveFilePreflightResult {
        val sourceUri = when (val resolution = probe.resolve(filePath)) {
            SourceDocumentResolution.Gone -> return DestructiveFilePreflightResult.SourceGone
            SourceDocumentResolution.Ambiguous -> return DestructiveFilePreflightResult.Ambiguous
            is SourceDocumentResolution.Present -> resolution.uri
        }

        val roots = configuredRoots()
        val owner = when {
            roots.music != null && probe.isUnderRoot(sourceUri, roots.music) ->
                KnownAudioRoot.Music to roots.music
            roots.newArrivals != null && probe.isUnderRoot(sourceUri, roots.newArrivals) ->
                KnownAudioRoot.NewArrivals to roots.newArrivals
            else -> return DestructiveFilePreflightResult.RootUnknown
        }

        when (probe.rootWriteState(owner.second)) {
            RootWriteState.GrantNotWritable ->
                return DestructiveFilePreflightResult.GrantNotWritable(owner.first)
            RootWriteState.Ambiguous -> return DestructiveFilePreflightResult.Ambiguous
            RootWriteState.Writable -> Unit
        }

        when (probe.canWrite(sourceUri)) {
            false -> return DestructiveFilePreflightResult.DocumentNotWritable
            null -> return DestructiveFilePreflightResult.Ambiguous
            true -> Unit
        }

        val actualSongId = probe.computeSongId(sourceUri)
            ?: return DestructiveFilePreflightResult.Ambiguous
        return if (actualSongId == expectedSongId) {
            DestructiveFilePreflightResult.Passed
        } else {
            DestructiveFilePreflightResult.IdentityMismatch
        }
    }

    suspend fun checkReplacement(
        filePath: String,
        expectedSongId: String,
    ): ReplacementFilePreflightResult {
        val sourceUri = when (val resolution = probe.resolve(filePath)) {
            SourceDocumentResolution.Gone -> return ReplacementFilePreflightResult.SourceGone
            SourceDocumentResolution.Ambiguous -> return ReplacementFilePreflightResult.Ambiguous
            is SourceDocumentResolution.Present -> resolution.uri
        }
        val actualSongId = probe.computeSongId(sourceUri)
            ?: return ReplacementFilePreflightResult.Ambiguous
        return if (actualSongId == expectedSongId) {
            ReplacementFilePreflightResult.Passed
        } else {
            ReplacementFilePreflightResult.IdentityMismatch
        }
    }

    companion object {
        fun create(context: Context): DestructiveFilePreflight {
            val appContext = context.applicationContext
            val preferences = PreferencesManager(appContext)
            return DestructiveFilePreflight(
                probe = AndroidAudioDocumentPreflightProbe(appContext),
                configuredRoots = {
                    ConfiguredAudioRoots(
                        music = preferences.musicFolderUri,
                        newArrivals = preferences.newArrivalsFolderUri,
                    )
                },
            )
        }
    }
}

private class AndroidAudioDocumentPreflightProbe(
    private val context: Context,
) : AudioDocumentPreflightProbe {
    override fun resolve(filePath: String): SourceDocumentResolution {
        val uri = runCatching { filePath.toUri() }.getOrNull()
            ?: return SourceDocumentResolution.Gone
        val document = try {
            DocumentFile.fromSingleUri(context, uri)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return SourceDocumentResolution.Ambiguous
        } ?: return SourceDocumentResolution.Gone

        return try {
            context.contentResolver.query(
                document.uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.count > 0) SourceDocumentResolution.Present(document.uri.toString())
                else SourceDocumentResolution.Gone
            } ?: SourceDocumentResolution.Ambiguous
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            SourceDocumentResolution.Ambiguous
        }
    }

    override suspend fun isUnderRoot(documentUri: String, rootUri: String): Boolean {
        val document = runCatching { documentUri.toUri() }.getOrNull() ?: return false
        val root = runCatching { rootUri.toUri() }.getOrNull() ?: return false
        return RootNesting.isUnderRoot(context, document, root)
    }

    override fun rootWriteState(rootUri: String): RootWriteState {
        val root = runCatching { rootUri.toUri() }.getOrNull()
            ?: return RootWriteState.Ambiguous
        val hasWriteGrant = try {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == root && it.isWritePermission
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return RootWriteState.Ambiguous
        }
        if (!hasWriteGrant) return RootWriteState.GrantNotWritable
        return if (isRootWritable(context, root) != null) {
            RootWriteState.Writable
        } else {
            RootWriteState.Ambiguous
        }
    }

    override fun canWrite(documentUri: String): Boolean? = try {
        DocumentFile.fromSingleUri(context, documentUri.toUri())?.canWrite()
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        null
    }

    override fun computeSongId(documentUri: String): String? = try {
        DocumentFile.fromSingleUri(context, documentUri.toUri())
            ?.let { HashUtils.computeSongId(it, context) }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        null
    }
}
