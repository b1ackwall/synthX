package com.v1per.synthx.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

internal class BackupPublicationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal data class PublishedBackup(
    val identity: BackupFileIdentity,
    val database: DocumentFile,
    val metadata: DocumentFile,
)

internal class SafBackupPublisher(private val context: Context) {
    private val resolver = context.contentResolver

    fun publish(
        tierDirectory: DocumentFile,
        identity: BackupFileIdentity,
        snapshot: File,
        digest: ByteDigest,
        metadataBytes: ByteArray,
    ): PublishedBackup {
        var temporaryDatabase: DocumentFile? = null
        var temporaryMetadata: DocumentFile? = null
        var namesReservedForAttempt = false
        try {
            requireNameAvailable(tierDirectory, identity)
            namesReservedForAttempt = true

            temporaryDatabase = tierDirectory.createFile(
                MIME_DATABASE,
                identity.temporaryDatabaseFileName,
            ) ?: throw BackupPublicationException("SAF provider could not create temporary database")
            requireDocumentName(temporaryDatabase, identity.temporaryDatabaseFileName)
            copySnapshot(snapshot, temporaryDatabase.uri)
            verifyExternalBytes(resolver, temporaryDatabase.uri, digest)

            temporaryMetadata = tierDirectory.createFile(
                MIME_JSON,
                identity.temporaryMetadataFileName,
            ) ?: throw BackupPublicationException("SAF provider could not create temporary metadata")
            requireDocumentName(temporaryMetadata, identity.temporaryMetadataFileName)
            writeBytes(temporaryMetadata.uri, metadataBytes, "metadata")

            if (!temporaryMetadata.renameTo(identity.metadataFileName)) {
                throw BackupPublicationException(
                    "SAF provider cannot publish metadata by renaming a temporary document"
                )
            }
            val finalMetadata = tierDirectory.findFile(identity.metadataFileName)
                ?: throw BackupPublicationException("Published metadata document is not visible")

            // The recognizable database name is deliberately published last.
            if (!temporaryDatabase.renameTo(identity.databaseFileName)) {
                throw BackupPublicationException(
                    "SAF provider cannot publish database by renaming a temporary document"
                )
            }
            val finalDatabase = tierDirectory.findFile(identity.databaseFileName)
                ?: throw BackupPublicationException("Published database document is not visible")
            // DocumentFile documents that a provider may implement rename by creating a new
            // document. Re-read the final URI as well so Success describes the published bytes,
            // not merely the pre-rename temporary document.
            verifyExternalBytes(resolver, finalDatabase.uri, digest)

            return PublishedBackup(identity, finalDatabase, finalMetadata)
        } catch (primary: Throwable) {
            cleanupAfterFailure(
                tierDirectory = tierDirectory,
                identity = identity,
                temporaryDatabase = temporaryDatabase,
                temporaryMetadata = temporaryMetadata,
                namesReservedForAttempt = namesReservedForAttempt,
                primary = primary,
            )
            if (primary is BackupPublicationException) throw primary
            throw BackupPublicationException("SAF backup publication failed", primary)
        }
    }

    internal fun verifyExternalBytes(uri: Uri, expected: ByteDigest) {
        verifyExternalBytes(resolver, uri, expected)
    }

    private fun copySnapshot(snapshot: File, destination: Uri) {
        val output = resolver.openOutputStream(destination, "w")
            ?: throw BackupPublicationException("Could not open temporary SAF database for writing")
        try {
            output.use { out -> FileInputStream(snapshot).use { input -> input.copyTo(out) } }
        } catch (error: Exception) {
            throw BackupPublicationException("Could not write temporary SAF database", error)
        }
    }

    private fun writeBytes(destination: Uri, bytes: ByteArray, label: String) {
        val output = resolver.openOutputStream(destination, "w")
            ?: throw BackupPublicationException("Could not open temporary SAF $label for writing")
        try {
            output.use { it.write(bytes) }
        } catch (error: Exception) {
            throw BackupPublicationException("Could not write temporary SAF $label", error)
        }
    }

    private fun requireNameAvailable(directory: DocumentFile, identity: BackupFileIdentity) {
        val names = listOf(
            identity.databaseFileName,
            identity.metadataFileName,
            identity.temporaryDatabaseFileName,
            identity.temporaryMetadataFileName,
        )
        if (names.any { directory.findFile(it) != null }) {
            throw BackupPublicationException("Backup filename collision in SAF destination")
        }
    }

    private fun requireDocumentName(document: DocumentFile, expected: String) {
        if (document.name != expected) {
            throw BackupPublicationException(
                "SAF provider changed temporary document name; safe publication is unsupported"
            )
        }
    }

    private fun cleanupAfterFailure(
        tierDirectory: DocumentFile,
        identity: BackupFileIdentity,
        temporaryDatabase: DocumentFile?,
        temporaryMetadata: DocumentFile?,
        namesReservedForAttempt: Boolean,
        primary: Throwable,
    ) {
        val cleanupTargets = buildList {
            add(temporaryDatabase)
            add(temporaryMetadata)
            add(tierDirectory.findFile(identity.temporaryDatabaseFileName))
            add(tierDirectory.findFile(identity.temporaryMetadataFileName))
            if (namesReservedForAttempt) {
                // These names were proven absent before this unique attempt began, so any exact
                // match now is a partially promoted artifact owned by this attempt.
                add(tierDirectory.findFile(identity.databaseFileName))
                add(tierDirectory.findFile(identity.metadataFileName))
            }
        }.filterNotNull().distinctBy { it.uri }

        cleanupTargets.forEach { target ->
            runCatching {
                if (!target.delete() && target.exists()) {
                    throw IllegalStateException("SAF provider refused to delete ${target.name}")
                }
            }.onFailure { cleanupError ->
                primary.addSuppressed(cleanupError)
                Log.w(TAG, "Could not remove failed backup artifact ${target.name}", cleanupError)
            }
        }
    }

    companion object {
        private const val TAG = "SafBackupPublisher"
        // Custom MIME types avoid providers appending a second extension to our deliberately
        // complete display names (for example, turning "x.json.partial" into "x.json.partial.json").
        private const val MIME_DATABASE = "application/x-synthx-backup"
        private const val MIME_JSON = "application/x-synthx-backup-metadata"

        internal fun verifyExternalBytes(
            resolver: ContentResolver,
            uri: Uri,
            expected: ByteDigest,
        ) {
            val actual = resolver.openInputStream(uri)?.use { input -> BackupHashing.hash(input) }
                ?: throw BackupPublicationException("Could not re-read temporary SAF database")
            if (actual.sizeBytes != expected.sizeBytes) {
                throw BackupPublicationException(
                    "SAF database byte count mismatch: expected ${expected.sizeBytes}, " +
                        "read ${actual.sizeBytes}"
                )
            }
            if (actual.sha256 != expected.sha256) {
                throw BackupPublicationException("SAF database SHA-256 mismatch")
            }
        }
    }
}
