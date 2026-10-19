package androidx.documentfile.provider

import android.net.Uri
import java.io.File

/**
 * Test-only real filesystem directory that corrupts [targetName]'s bytes immediately after a
 * successful rename to it — simulating a provider that silently alters a document's content
 * during/after rename completion without the rename call itself failing or the document's
 * reported name changing (the threat model: syntactically-valid, size-preserving damage).
 */
internal class CorruptingRenameDocumentFile(private val root: File, private val targetName: String) :
    DocumentFile(null) {
    private val delegate = fromFile(root)

    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        val created = delegate.createFile(mimeType, displayName) ?: return null
        return CorruptOnRenameDocumentFile(created, root, targetName)
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

private class CorruptOnRenameDocumentFile(
    private val delegate: DocumentFile,
    private val root: File,
    private val targetName: String,
) : DocumentFile(null) {
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
    override fun findFile(displayName: String): DocumentFile? = delegate.findFile(displayName)

    /**
     * Flips one byte in place after a successful rename to [targetName] — same size, different
     * content, and the rename call itself still reports success, matching a provider bug the
     * caller's own success/failure signal cannot detect.
     */
    override fun renameTo(displayName: String): Boolean {
        val renamed = delegate.renameTo(displayName)
        if (renamed && displayName == targetName) {
            val file = File(root, targetName)
            val bytes = file.readBytes()
            if (bytes.isNotEmpty()) {
                bytes[0] = (bytes[0] + 1).toByte()
                file.writeBytes(bytes)
            }
        }
        return renamed
    }
}
