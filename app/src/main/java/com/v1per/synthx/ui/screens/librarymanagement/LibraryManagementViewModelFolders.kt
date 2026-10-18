package com.v1per.synthx.ui.screens.librarymanagement

import android.annotation.SuppressLint
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.await
import com.v1per.synthx.R
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.data.scanner.RootNesting
import com.v1per.synthx.ui.common.launchCatching
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementViewModel.ConfiguredFolderRole

// LibraryManagementViewModel's folder-save paths and the SAF grant rules they apply.

internal fun LibraryManagementViewModel.saveMusicFolderInternal(uri: Uri) {
    // Backup first, then persist: the snapshot must capture the library as it was
    // under the old folder, before the change invalidates it. Validation precedes even
    // that — a rejected pick must leave no trace, backup included.
    launchCatching(onError = {
        _folderError.showTemporarily(context.getString(R.string.error_folder_save_failed))
    }) {
        val rejected = rejectIfNested(
            candidate = uri,
            other = _newArrivalsFolderUri.value,
            onError = { _folderError.value = it },
        )
        if (rejected) return@launchCatching

        // P7-T3 / R7-F08: nothing has actually been backed up yet at this point on a fresh
        // install — the backup folder is a later, separate step in the same wizard — so
        // attempting the snapshot here would only ever throw BackupNotConfiguredException,
        // which BackupOutcomeStore would then durably record as a genuine backup *failure*.
        // That surfaces as a false "Failed" health warning the very first time the user
        // opens Backups and Restore, for an operation that was never actually possible.
        // Skip the attempt entirely (not merely catch the exception) so no outcome is
        // recorded at all — a later re-pick from Library Management, once a backup folder
        // is actually configured, still takes its snapshot as before.
        if (preferencesManager.backupFolderUri != null) {
            backupManager.createBackup(BackupTier.Auto, BackupReason.FOLDER_CHANGE)
        }

        releaseGrantIfSuperseded(
            old = _musicFolderUri.value,
            new = uri,
            supersededRole = ConfiguredFolderRole.MUSIC,
        )
        preferencesManager.musicFolderUri = uri.toString()
        _musicFolderUri.value = uri
        clearFolderError()
        _localStatus.value = null
        // REPLACE, not KEEP: any scan still running is walking the folder the user just
        // replaced, so its results are already stale.
        LibraryScanWorker.enqueue(context, ExistingWorkPolicy.REPLACE)
    }
}

internal fun LibraryManagementViewModel.saveRecoveryMusicFolderInternal(uri: Uri) {
    launchCatching(onError = {
        _folderError.showTemporarily(context.getString(R.string.error_folder_save_failed))
    }) {
        val rejected = rejectIfNested(
            candidate = uri,
            other = _newArrivalsFolderUri.value,
            onError = { _folderError.value = it },
        )
        if (rejected) return@launchCatching

        releaseGrantIfSuperseded(
            old = _musicFolderUri.value,
            new = uri,
            supersededRole = ConfiguredFolderRole.MUSIC,
        )
        preferencesManager.musicFolderUri = uri.toString()
        _musicFolderUri.value = uri
        clearFolderError()
    }
}

internal fun LibraryManagementViewModel.saveRecoveryNewArrivalsFolderInternal(uri: Uri) {
    launchCatching(onError = {
        _newArrivalsFolderError.showTemporarily(context.getString(R.string.error_folder_save_failed))
    }) {
        val rejected = rejectIfNested(
            candidate = uri,
            other = _musicFolderUri.value,
            onError = { _newArrivalsFolderError.value = it },
        )
        if (rejected) return@launchCatching

        releaseGrantIfSuperseded(
            old = _newArrivalsFolderUri.value,
            new = uri,
            supersededRole = ConfiguredFolderRole.NEW_ARRIVALS,
        )
        preferencesManager.newArrivalsFolderUri = uri.toString()
        _newArrivalsFolderUri.value = uri
        _newArrivalsFolderError.value = null
    }
}

// WorkManager's await is restricted API, see #243.
@SuppressLint("RestrictedApi")
internal fun LibraryManagementViewModel.saveNewArrivalsFolderInternal(uri: Uri) {
    launchCatching(onError = {
        _newArrivalsFolderError.showTemporarily(context.getString(R.string.error_folder_save_failed))
    }) {
        val rejected = rejectIfNested(
            candidate = uri,
            other = _musicFolderUri.value,
            onError = { _newArrivalsFolderError.value = it },
        )
        if (rejected) return@launchCatching

        releaseGrantIfSuperseded(
            old = _newArrivalsFolderUri.value,
            new = uri,
            supersededRole = ConfiguredFolderRole.NEW_ARRIVALS,
        )
        preferencesManager.newArrivalsFolderUri = uri.toString()
        _newArrivalsFolderUri.value = uri

        val anotherScanRunning = workManager
            .getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME)
            .await()
            .any { !it.state.isFinished }
        _newArrivalsFolderError.value = null
        val enqueued = NewArrivalsScanWorker.enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        // Informational, on its own channel (F18): cleared when this request stops waiting.
        _deferredNewArrivalsScanId.value = if (anotherScanRunning) enqueued.id else null
    }
}

/**
 * Guards both audio-root pickers against overlapping trees — see [RootNesting] for why
 * nesting is unrecoverable rather than merely untidy. Returns `true` when the pick was
 * rejected, in which case nothing is persisted and the previously configured URI stands
 * unchanged; the caller must return without saving.
 *
 * The grant the picker just took is released only when no configured folder role still
 * depends on that URI. In particular, a [RootNesting.Relation.SAME] conflict can mean the
 * candidate is another role's active grant; that grant must remain intact even though this
 * pick is rejected. Grant release stays behind validation so genuinely orphaned rejected
 * picks do not leave unused persisted permissions behind.
 */
private suspend fun LibraryManagementViewModel.rejectIfNested(
    candidate: Uri,
    other: Uri?,
    onError: (String) -> Unit,
): Boolean {
    if (other == null) return false
    val relation = RootNesting.detect(context, candidate, other)
    if (!relation.isConflict) return false

    // UNDETERMINED means nesting couldn't be ruled out (opaque provider ids) rather than
    // confirmed — fail closed with a message that doesn't overclaim actual nesting.
    val message = if (relation == RootNesting.Relation.UNDETERMINED) {
        context.getString(R.string.error_folders_nesting_undetermined)
    } else {
        context.getString(R.string.error_folders_nested)
    }
    onError(message)
    if (!isGrantOwnedByConfiguredRole(candidate)) releaseGrantWhenIdle(candidate)
    return true
}

/** No-op when the old grant is still needed by the replacement or another configured role. */
private suspend fun LibraryManagementViewModel.releaseGrantIfSuperseded(
    old: Uri?,
    new: Uri,
    supersededRole: ConfiguredFolderRole,
) {
    if (old != null && old != new &&
        !isGrantOwnedByConfiguredRole(old, excluding = supersededRole)
    ) {
        releaseGrantWhenIdle(old)
    }
}

/** Persisted SAF permissions are URI-wide, not separate leases for each configured role. */
private fun LibraryManagementViewModel.isGrantOwnedByConfiguredRole(
    uri: Uri,
    excluding: ConfiguredFolderRole? = null,
): Boolean {
    val configuredRoles = listOf(
        ConfiguredFolderRole.MUSIC to preferencesManager.musicFolderUri,
        ConfiguredFolderRole.NEW_ARRIVALS to preferencesManager.newArrivalsFolderUri,
        ConfiguredFolderRole.BACKUP to preferencesManager.backupFolderUri,
    )
    return configuredRoles.any { (role, storedUri) ->
        role != excluding && storedUri?.let(Uri::parse) == uri
    }
}

/**
 * Every grant release goes through here — all four folder-save paths and [rejectIfNested].
 * The tree is first closed to new file operations, then any graduation still copying into or
 * deleting out of it is waited out, and only then is the grant revoked (P2-T1): revoking it
 * mid-move used to fail the move part-way, after the original could already be gone.
 */
private suspend fun LibraryManagementViewModel.releaseGrantWhenIdle(uri: Uri) {
    FileOperationCoordinator.retireRootWhenIdle(FileOperationCoordinator.rootKey(uri)) {
        releaseGrant(uri)
    }
}

/**
 * Both flags, matching what both audio roots are now taken with — graduation writes into the
 * music folder and deletes out of New Arrivals, so each picker takes read+write. Releasing
 * only read would strand a superseded root's write grant permanently, against a URI nothing
 * points at any more.
 *
 * Kept as the wider set rather than something per-root: this one function serves both roots
 * and [rejectIfNested]'s rejected candidate, and naming a flag that was never held is not an
 * error — the platform releases the intersection of what is asked for and what is actually
 * persisted, so a grant predating the write widening still releases cleanly.
 */
private fun LibraryManagementViewModel.releaseGrant(uri: Uri) {
    try {
        releasePersistableGrant(uri)
    } catch (e: SecurityException) {
        // Already gone — nothing to release
    }
}
