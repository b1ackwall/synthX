package androidx.documentfile.provider

import android.net.Uri
import java.io.File

/**
 * Test-only real filesystem directory whose created documents refuse to rename to
 * [collidingName] — simulating a provider that refuses a rename because another writer has
 * taken the destination name between the caller's collision check and the actual rename.
 */
internal class CollidingRenameDocumentFile(root: File, private val collidingName: String) : DocumentFile(null) {
    private val delegate = fromFile(root)

    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        val created = delegate.createFile(mimeType, displayName) ?: return null
        return RefusingRenameDocumentFile(created, collidingName)
    }

    override fun createDirectory(displayName: String): DocumentFile? = delegate.createDirectory(displayName)
    override fun getUri(): Uri = delegate.uri
    override fun getName(): String? = delegate.name
    override fun getType(): String? = delegate.type
    override fun isDirectory(): Boolean = delegate.isDirectory
    override fun isFile(): Boolean = delegate.isFile
    override fun isVirtual(): Boolean = delegate.isVirtual
    override fun lastModified(): Long = delegate.lastModified()
    override fun length(): Long = delegate.length()
    override fun canRead(): Boolean = delegate.canRead()
    override fun canWrite(): Boolean = delegate.canWrite()
    override fun delete(): Boolean = delegate.delete()
    override fun exists(): Boolean = delegate.exists()
    override fun listFiles(): Array<DocumentFile> = delegate.listFiles()
    override fun renameTo(displayName: String): Boolean = delegate.renameTo(displayName)
    override fun findFile(displayName: String): DocumentFile? = delegate.findFile(displayName)
}

/**
 * Test-only wrapper reporting a fixed, deliberately non-`MimeTypeMap`-resolvable [mimeType] for
 * [delegate] regardless of its file extension. `RawDocumentFile.createFile` appends whatever
 * extension `MimeTypeMap` associates with the MIME type it's given onto the display name it's
 * given — including, on some devices, for `application/octet-stream` (mapped to `.bin`) — a real
 * `RawDocumentFile` quirk unrelated to any real tree-document SAF provider. Same technique
 * `SafBackupPublisher` uses (custom `application/x-synthx-*` MIME types) to avoid it.
 */
internal class FixedTypeDocumentFile(private val delegate: DocumentFile, private val mimeType: String) :
    DocumentFile(null) {
    override fun createFile(mimeType: String, displayName: String): DocumentFile? =
        delegate.createFile(mimeType, displayName)
    override fun createDirectory(displayName: String): DocumentFile? = delegate.createDirectory(displayName)
    override fun getUri(): Uri = delegate.uri
    override fun getName(): String? = delegate.name
    override fun getType(): String = mimeType
    override fun isDirectory(): Boolean = delegate.isDirectory
    override fun isFile(): Boolean = delegate.isFile
    override fun isVirtual(): Boolean = delegate.isVirtual
    override fun lastModified(): Long = delegate.lastModified()
    override fun length(): Long = delegate.length()
    override fun canRead(): Boolean = delegate.canRead()
    override fun canWrite(): Boolean = delegate.canWrite()
    override fun delete(): Boolean = delegate.delete()
    override fun exists(): Boolean = delegate.exists()
    override fun listFiles(): Array<DocumentFile> = delegate.listFiles()
    override fun renameTo(displayName: String): Boolean = delegate.renameTo(displayName)
    override fun findFile(displayName: String): DocumentFile? = delegate.findFile(displayName)
}

/** Wraps a just-created temp document; refuses to rename specifically to [collidingName]. */
private class RefusingRenameDocumentFile(private val delegate: DocumentFile, private val collidingName: String) :
    DocumentFile(null) {
    override fun createFile(mimeType: String, displayName: String): DocumentFile? =
        delegate.createFile(mimeType, displayName)
    override fun createDirectory(displayName: String): DocumentFile? = delegate.createDirectory(displayName)
    override fun getUri(): Uri = delegate.uri
    override fun getName(): String? = delegate.name
    override fun getType(): String? = delegate.type
    override fun isDirectory(): Boolean = delegate.isDirectory
    override fun isFile(): Boolean = delegate.isFile
    override fun isVirtual(): Boolean = delegate.isVirtual
    override fun lastModified(): Long = delegate.lastModified()
    override fun length(): Long = delegate.length()
    override fun canRead(): Boolean = delegate.canRead()
    override fun canWrite(): Boolean = delegate.canWrite()
    override fun delete(): Boolean = delegate.delete()
    override fun exists(): Boolean = delegate.exists()
    override fun listFiles(): Array<DocumentFile> = delegate.listFiles()
    override fun renameTo(displayName: String): Boolean =
        if (displayName == collidingName) false else delegate.renameTo(displayName)
}
