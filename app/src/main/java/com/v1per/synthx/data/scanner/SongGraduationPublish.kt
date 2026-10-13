package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.backup.BackupHashing
import com.v1per.synthx.data.backup.ByteDigest
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

private const val TAG = "SongGraduation"

/** Outcome of [copyToGraduationTemp]. */
internal sealed interface GraduationCopyOutcome {
    data class Copied(val temp: DocumentFile, val finalName: String, val sourceDigest: ByteDigest) :
        GraduationCopyOutcome
    data class Failed(val reason: String) : GraduationCopyOutcome
}

/** Outcome of [verifyAndPublishGraduationTemp] (and so of [copyVerifyAndPublish]). */
internal sealed interface GraduationPublishResult {
    /** [document] is the renamed temp `DocumentFile` itself — see the KDoc below for why. */
    data class Published(val document: DocumentFile) : GraduationPublishResult
    data class Failed(val reason: String) : GraduationPublishResult
}

/** Whether a failed graduation removed the temp/published destination it created. */
internal sealed interface GraduationCleanupResult {
    data object Cleaned : GraduationCleanupResult
    data class Failed(val uri: Uri) : GraduationCleanupResult
}

/**
 * Copies [source] into [destinationRoot] under a temp name, computing a digest of the bytes as
 * they're streamed across. Split out from verification/publication (below) so a test can corrupt
 * the resulting temp document's actual on-disk bytes — simulating a provider that persists
 * something other than what it was handed — and confirm the copy-integrity check in
 * [verifyAndPublishGraduationTemp] catches that divergence.
 */
internal fun copyToGraduationTemp(
    context: Context,
    source: DocumentFile,
    destinationRoot: DocumentFile,
    displayName: String,
    copy: (Uri, Uri) -> ByteDigest = { sourceUri, destinationUri ->
        copyBytes(context, sourceUri, destinationUri)
    },
): GraduationCopyOutcome {
    // Same-named-file collision: an explicit, predictable " (1)", " (2)"... suffix rather than
    // relying on provider auto-rename behavior.
    val finalName = resolveCollisionFreeName(destinationRoot, displayName)
    val mimeType = source.type ?: "application/octet-stream"
    // Scans never enumerate this name — see isScannableAudioName.
    val tempName = "$GRADUATION_TEMP_PREFIX$finalName"

    val temp = destinationRoot.createFile(mimeType, tempName)
        ?: return GraduationCopyOutcome.Failed("Could not create destination file")
    if (temp.name != tempName) {
        return GraduationCopyOutcome.Failed(
            reasonAfterTempCleanup("Destination provider changed the temporary file name", temp),
        )
    }

    val sourceDigest = try {
        copy(source.uri, temp.uri)
    } catch (e: CancellationException) {
        cleanupGraduationDoc(temp)
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Copy to destination failed", e)
        return GraduationCopyOutcome.Failed(
            reasonAfterTempCleanup("Could not copy file to the library folder", temp),
        )
    }

    return GraduationCopyOutcome.Copied(temp, finalName, sourceDigest)
}

/**
 * Verifies [temp] two independent ways and publishes it by renaming it to [finalName]. Mirrors
 * [com.v1per.synthx.data.backup.SafBackupPublisher]'s copy-to-temp -> verify -> rename-to-final
 * shape.
 *
 * On success, [GraduationPublishResult.Published.document] IS the renamed [temp]
 * `DocumentFile` — `TreeDocumentFile.renameTo` mutates its own backing `Uri` to the provider's
 * authoritative post-rename `Uri` when it succeeds, so `temp` already reflects the published
 * document once `renameTo` returns true. A subsequent `destinationRoot.findFile(finalName)`
 * name-based lookup is therefore not just redundant but actively risky: it can resolve a
 * *different* document than the one just verified (a stale directory listing, or another
 * document that happens to share the name), silently publishing the wrong file. `temp` is the
 * only reliable continuity mechanism across the rename — SAF document ids are explicitly allowed
 * to change on rename for path-based providers, so there is no other stable identifier to check
 * it against.
 *
 * On any failure, [temp] (or its renamed form) is best-effort deleted before returning — a caller
 * that receives [GraduationPublishResult.Failed] has nothing left to clean up.
 */
internal fun verifyAndPublishGraduationTemp(
    context: Context,
    destinationRoot: DocumentFile,
    temp: DocumentFile,
    finalName: String,
    expectedSongId: String,
    sourceDigest: ByteDigest,
): GraduationPublishResult {
    // Identity check: cheap, sampled (first/last 64KB + size), matches library-identity
    // elsewhere (e.g. ScanExtraction.kt). Left completely untouched — see HashUtils.
    val copiedId = HashUtils.computeSongId(temp, context)
    if (copiedId == null || copiedId != expectedSongId) {
        return GraduationPublishResult.Failed(
            reasonAfterTempCleanup("Copied file failed verification", temp),
        )
    }

    // Copy-integrity check (F12) — distinct from the identity check above and NOT a replacement
    // for it. computeSongId only samples the first/last 64KB plus size, by design, for a
    // cross-scan identity role; it can miss corruption confined to the untouched middle of a
    // larger file. This independently re-reads every byte the provider actually persisted at
    // `temp.uri` and compares it against the digest captured while those exact bytes were
    // streamed to it in copyBytes (via [copyToGraduationTemp]) — the only way to know the
    // provider genuinely persisted what it was handed, rather than trusting that "bytes we
    // wrote" equals "bytes it kept."
    val actualDigest = try {
        context.contentResolver.openInputStream(temp.uri)?.use { BackupHashing.hash(it) }
    } catch (e: Exception) {
        null
    }
    if (actualDigest == null) {
        return GraduationPublishResult.Failed(
            reasonAfterTempCleanup("Could not re-read copied file for verification", temp),
        )
    }
    if (actualDigest.sizeBytes != sourceDigest.sizeBytes || actualDigest.sha256 != sourceDigest.sha256) {
        return GraduationPublishResult.Failed(
            reasonAfterTempCleanup("Copied file failed full-content verification", temp),
        )
    }

    if (!temp.renameTo(finalName)) {
        // A provider that refuses because the name is now taken (resolveCollisionFreeName's
        // check-then-act above raced with another writer onto the same destination name) is a
        // distinguishable, more actionable failure than a generic publish failure.
        val collided = runCatching { destinationRoot.findFile(finalName) != null }.getOrDefault(false)
        return GraduationPublishResult.Failed(
            reasonAfterTempCleanup(if (collided) {
                "Could not publish: another file named \"$finalName\" was created in the " +
                    "library folder at the same time. Try again."
            } else {
                "Could not publish destination file."
            }, temp),
        )
    }
    // temp's own Uri now reflects the rename (see the KDoc above) — nothing left to look up.
    if (temp.name != finalName) {
        return GraduationPublishResult.Failed(
            reasonAfterTempCleanup("Destination provider renamed to an unexpected name", temp),
        )
    }

    return GraduationPublishResult.Published(temp)
}

internal fun cleanupGraduationDoc(doc: DocumentFile): GraduationCleanupResult {
    val deleted = try {
        doc.delete()
    } catch (e: Exception) {
        Log.w(TAG, "Destination cleanup threw; artifact may remain at ${doc.uri}", e)
        false
    }
    if (deleted) return GraduationCleanupResult.Cleaned

    // Some providers report false for an already-gone document. Treat that as clean only when a
    // follow-up query confirms absence; otherwise preserve the failure and its recovery evidence.
    val stillExists = runCatching { doc.exists() }.getOrDefault(true)
    if (!stillExists) return GraduationCleanupResult.Cleaned

    Log.w(TAG, "Destination cleanup was refused; artifact remains at ${doc.uri}")
    return GraduationCleanupResult.Failed(doc.uri)
}

private fun reasonAfterTempCleanup(reason: String, temp: DocumentFile): String =
    if (cleanupGraduationDoc(temp) is GraduationCleanupResult.Failed) {
        "$reason Cleanup also failed, so a temporary destination file may remain on disk."
    } else {
        reason
    }

internal fun reasonAfterPublishedCleanup(reason: String, published: DocumentFile): String =
    if (cleanupGraduationDoc(published) is GraduationCleanupResult.Failed) {
        "$reason Cleanup also failed, so the copied file may remain in your Music folder and " +
            "may be found by a later library scan."
    } else {
        reason
    }

/**
 * Convenience wrapper combining [copyToGraduationTemp] and [verifyAndPublishGraduationTemp] —
 * the sequence [graduateAdmitted] actually runs. Split into two functions above purely for
 * testability (see [copyToGraduationTemp]'s KDoc); production code only needs this combined form.
 */
internal suspend fun copyVerifyAndPublish(
    context: Context,
    source: DocumentFile,
    destinationRoot: DocumentFile,
    displayName: String,
    expectedSongId: String,
    onStage: suspend (GraduationStage) -> Unit = {},
): GraduationPublishResult {
    onStage(GraduationStage.Copying)
    val copyOutcome = copyToGraduationTemp(context, source, destinationRoot, displayName)
    val copied = when (copyOutcome) {
        is GraduationCopyOutcome.Failed -> return GraduationPublishResult.Failed(copyOutcome.reason)
        is GraduationCopyOutcome.Copied -> copyOutcome
    }

    onStage(GraduationStage.Verifying)
    return verifyAndPublishGraduationTemp(
        context,
        destinationRoot,
        copied.temp,
        copied.finalName,
        expectedSongId,
        copied.sourceDigest,
    )
}

/** First free name in [directory] starting from [name] — " (1)", " (2)"... inserted before the
 *  extension, explicit and predictable rather than relying on provider auto-rename. */
private fun resolveCollisionFreeName(directory: DocumentFile, name: String): String {
    if (directory.findFile(name) == null) return name
    val dotIndex = name.lastIndexOf('.')
    val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
    val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
    var suffix = 1
    while (true) {
        val candidate = "$base ($suffix)$ext"
        if (directory.findFile(candidate) == null) return candidate
        suffix++
    }
}

/**
 * Copies [source] to [destination], returning a running SHA-256 + byte count of the bytes as
 * they're written — reusing [BackupHashing]'s read loop via [TeeInputStream] instead of a plain
 * `copyTo` (same base approach as SafBackupPublisher.copySnapshot). This digests the copy as it
 * happens rather than re-reading the source a second time afterward purely to hash it.
 */
private fun copyBytes(context: Context, source: Uri, destination: Uri): ByteDigest {
    val resolver = context.contentResolver
    val output = resolver.openOutputStream(destination, "w")
        ?: throw IllegalStateException("Could not open destination for writing")
    return output.use { out ->
        val input = resolver.openInputStream(source)
            ?: throw IllegalStateException("Could not open source for reading")
        input.use { raw -> BackupHashing.hash(TeeInputStream(raw, out)) }
    }
}

/** Mirrors every byte actually read from [source] into [sink] as it's read, so a single read
 *  loop (here, [BackupHashing.hash]'s) can copy and digest a stream in one pass. */
private class TeeInputStream(source: InputStream, private val sink: OutputStream) : FilterInputStream(source) {
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) sink.write(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) sink.write(b, off, n)
        return n
    }
}
