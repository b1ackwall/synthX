package com.v1per.synthx.ui.screens.librarymanagement

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Converts a SAF tree URI into a human-readable display string.
 *
 * Only the built-in ExternalStorageProvider's primary volume ("Internal Storage") decodes
 * to a reliable path — its tree document id is the well-known "primary:relative/path"
 * format. Any other volume (SD card, third-party document provider) has a provider-defined
 * id with no guaranteed structure, so this falls back to SAF's own display name, and
 * finally to the raw URI string so the caller never has nothing to show.
 */
internal object FolderPathFormatter {

    private const val PRIMARY_VOLUME_PREFIX = "primary:"
    private const val INTERNAL_STORAGE_LABEL = "Internal Storage"

    fun format(context: Context, uri: Uri): String {
        val primaryRelativePath = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull()
            ?.takeIf { it.startsWith(PRIMARY_VOLUME_PREFIX) }
            ?.removePrefix(PRIMARY_VOLUME_PREFIX)
        if (primaryRelativePath != null) {
            return if (primaryRelativePath.isEmpty()) {
                INTERNAL_STORAGE_LABEL
            } else {
                "$INTERNAL_STORAGE_LABEL/$primaryRelativePath"
            }
        }

        val documentDisplayName = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
            .getOrNull()
        if (!documentDisplayName.isNullOrBlank()) return documentDisplayName

        return uri.toString()
    }
}
