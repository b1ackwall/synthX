package com.v1per.synthx.ui.screens.librarymanagement

import android.content.Context
import android.content.Intent
import android.net.Uri

/** What a system folder-picker result means for the row that launched it (P9-T4 / F09). */
internal sealed interface FolderPickResult<out U> {
    /** The picker returned a folder and its grant was persisted — safe to hand to a ViewModel. */
    data class Granted<U>(val uri: U) : FolderPickResult<U>

    /** The user backed out of the picker. Nothing was picked, so nothing failed. */
    data object Cancelled : FolderPickResult<Nothing>

    /** The picker returned a folder but the provider would not grant persistable access. */
    data object GrantRefused : FolderPickResult<Nothing>
}

/**
 * The decision every folder-picker callback used to inline: a null result is a cancel, and a
 * [SecurityException] from taking the persistable grant is a refusal.
 *
 * Deliberately no attempt to tell "this provider structurally cannot grant persistable access"
 * (some cloud/SAF providers, USB/OTG roots) apart from a transient failure: `SecurityException`
 * carries only a free-text, framework-dependent message and the `OpenDocumentTree` contract drops
 * the result intent's flags. Callers therefore show the one message that is right in both cases.
 *
 * Generic over the URI type only so the rule is testable in a plain JVM test, where `android.net.Uri`
 * is a stub; production always passes [Uri].
 */
internal fun <U : Any> classifyPickResult(picked: U?, takePermission: (U) -> Unit): FolderPickResult<U> {
    if (picked == null) return FolderPickResult.Cancelled
    return try {
        takePermission(picked)
        FolderPickResult.Granted(picked)
    } catch (e: SecurityException) {
        FolderPickResult.GrantRefused
    }
}

/**
 * Routes a picker result: [onPicked] for a persisted grant, [onGrantRefused] for a refused one,
 * and nothing at all for a cancel.
 *
 * [onPicked] runs outside the permission try/catch, so a failure while *saving* an accepted folder
 * can no longer be misreported as a permission problem.
 */
internal fun <U : Any> handleFolderPick(
    picked: U?,
    takePermission: (U) -> Unit,
    onPicked: (U) -> Unit,
    onGrantRefused: () -> Unit,
) {
    when (val result = classifyPickResult(picked, takePermission)) {
        is FolderPickResult.Granted -> onPicked(result.uri)
        FolderPickResult.GrantRefused -> onGrantRefused()
        FolderPickResult.Cancelled -> Unit
    }
}

/**
 * Read + write, for every picker: graduation moves files into the music tree and deletes
 * originals from New Arrivals, and the backup folder is written to.
 */
internal fun Context.takeFolderGrant(uri: Uri) {
    contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}

/** The wizard's mandatory music-folder step: Done and the explanatory hint move together. */
internal data class MusicFolderStepGate(val doneEnabled: Boolean, val showRequiredHint: Boolean)

internal fun musicFolderStepGate(musicFolderSelected: Boolean) = MusicFolderStepGate(
    doneEnabled = musicFolderSelected,
    showRequiredHint = !musicFolderSelected,
)
