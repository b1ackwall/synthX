package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Whether a backup folder URI is actually usable right now — reused for re-validating a freshly
 * picked folder ([BackupsAndRestoreViewModel]) and for re-checking an already-persisted one
 * (on screen entry, and once at app launch via [BackupFolderLaunchCheck]).
 *
 * The grant check here looks for [android.content.UriPermission.isWritePermission], not just
 * read — the backup folder is written to on every snapshot. AudioFileWalk.isRootReachable is the
 * read-only counterpart, used where only readability matters (scanning, missing-detection); its
 * sibling `isRootWritable` makes the same write-grant check for the audio roots, which are also
 * write-held now.
 */
internal object BackupFolderHealth {

    sealed interface Result {
        data object Healthy : Result
        /** The persisted SAF grant no longer includes write access, or is gone entirely. */
        data object GrantLost : Result
        /** The grant is present but the tree no longer resolves to a real, writable directory. */
        data object Unwritable : Result
    }

    /**
     * [ensureFolderStructure] is reused as the write-capability probe rather than duplicated:
     * a write-permission flag being present doesn't mean the provider still actually honors
     * writes (revoked out-of-band, an ejected/remounted volume, a provider quirk) — the same
     * provider-flakiness reasoning behind isRootReachable's explicit reachability probe.
     */
    fun check(context: Context, backupManager: BackupManager, uri: Uri): Result {
        val hasWriteGrant = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!hasWriteGrant) return Result.GrantLost

        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        if (root?.isDirectory != true) return Result.GrantLost

        val writable = runCatching { backupManager.ensureFolderStructure(uri) }.getOrDefault(false)
        return if (writable) Result.Healthy else Result.Unwritable
    }
}
