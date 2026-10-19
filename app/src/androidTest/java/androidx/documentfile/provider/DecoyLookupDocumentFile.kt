package androidx.documentfile.provider

import android.net.Uri
import java.io.File

/**
 * Test-only real filesystem directory whose [findFile] resolves [decoyName] to a *different*
 * document ([decoy]) once a real document named [decoyName] exists in [root] — simulating a SAF
 * tree provider whose directory listing lags or diverges from the document the caller just
 * renamed (the same threat graduation's publish guards against). Before that real document exists the lookup
 * behaves normally, so a caller's up-front "is this name free?" check still sees the name as free.
 *
 * A caller that re-resolves its just-renamed document by name gets [decoy] back; a caller that
 * keeps using the rename-mutated `DocumentFile` never observes it.
 */
internal class DecoyLookupDocumentFile(
    private val root: File,
    private val decoyName: String,
    private val decoy: File,
) : DocumentFile(null) {
    private val delegate = fromFile(root)

    override fun findFile(displayName: String): DocumentFile? {
        if (displayName == decoyName && File(root, decoyName).exists()) return fromFile(decoy)
        return delegate.findFile(displayName)
    }

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
    override fun renameTo(displayName: String): Boolean = delegate.renameTo(displayName)
}
