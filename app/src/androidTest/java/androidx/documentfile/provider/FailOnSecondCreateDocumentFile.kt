package androidx.documentfile.provider

import android.net.Uri
import java.io.File

/** Test-only raw directory that simulates a provider refusing metadata creation. */
internal class FailOnSecondCreateDocumentFile(root: File) : DocumentFile(null) {
    private val delegate = fromFile(root)
    private var createCount = 0

    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        createCount += 1
        return if (createCount == 2) null else delegate.createFile(mimeType, displayName)
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
}
