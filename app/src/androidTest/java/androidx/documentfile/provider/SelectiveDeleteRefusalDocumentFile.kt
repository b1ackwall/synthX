package androidx.documentfile.provider

import android.net.Uri
import java.io.File

/**
 * Test-only real filesystem directory that makes [refuseNames] report `delete()` failure — either
 * by returning `false` (a provider that declines without throwing) or, when the same name is also
 * listed in [throwNames], by throwing instead — without actually removing those files, and deletes
 * everything else normally. Used to simulate R8-A12's "provider refuses/throws on a deletion"
 * scenarios against a real file tree instead of a genuine SAF grant.
 */
internal class SelectiveDeleteRefusalDocumentFile(
    private val root: File,
    private val refuseNames: Set<String>,
    private val throwNames: Set<String> = emptySet(),
) : DocumentFile(null) {
    private val delegate = fromFile(root)

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
    override fun renameTo(displayName: String): Boolean = delegate.renameTo(displayName)
    override fun findFile(displayName: String): DocumentFile? =
        delegate.findFile(displayName)?.let { wrap(it) }

    override fun listFiles(): Array<DocumentFile> = delegate.listFiles().map(::wrap).toTypedArray()

    // RefusingDeleteDocumentFile (Boolean-false refusal) is reused from CollidingRenameDocumentFile.kt
    // rather than redeclared here — only the throwing variant is new.
    private fun wrap(child: DocumentFile): DocumentFile = when (child.name) {
        in throwNames -> ThrowingDeleteDocumentFile(child)
        in refuseNames -> RefusingDeleteDocumentFile(child)
        else -> child
    }
}

private class ThrowingDeleteDocumentFile(private val delegate: DocumentFile) : DocumentFile(null) {
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
    override fun delete(): Boolean = throw IllegalStateException("Simulated provider delete failure")
    override fun exists(): Boolean = delegate.exists()
    override fun listFiles(): Array<DocumentFile> = delegate.listFiles()
    override fun renameTo(displayName: String): Boolean = delegate.renameTo(displayName)
}
