package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException

internal enum class DocumentExistence { Present, ConfirmedAbsent, Unknown }

internal fun interface DocumentExistenceProbe {
    fun exists(uri: Uri): DocumentExistence
}

internal class AndroidDocumentExistenceProbe(private val context: Context) : DocumentExistenceProbe {
    override fun exists(uri: Uri): DocumentExistence {
        val document = try {
            DocumentFile.fromSingleUri(context, uri)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return DocumentExistence.Unknown
        } ?: return DocumentExistence.Unknown

        return try {
            context.contentResolver.query(
                document.uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                val extras = cursor.extras
                if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                    extras.containsKey(DocumentsContract.EXTRA_ERROR)
                ) {
                    DocumentExistence.Unknown
                } else if (cursor.count > 0) {
                    DocumentExistence.Present
                } else {
                    DocumentExistence.ConfirmedAbsent
                }
            } ?: DocumentExistence.Unknown
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            DocumentExistence.Unknown
        }
    }
}
