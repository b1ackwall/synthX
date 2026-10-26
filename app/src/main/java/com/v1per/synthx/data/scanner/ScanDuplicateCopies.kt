package com.v1per.synthx.data.scanner

import android.net.Uri
import androidx.core.net.toUri
import com.v1per.synthx.data.db.SongEntity

/**
 * Byte-identical files share one content id and therefore one song row. These helpers pick the
 * copy that row points at, so the choice never depends on walk order or on which root a scan ran
 * for. A copy under the Music root outranks a copy under any other root; among copies of the same
 * rank the lowest path (plain string order) wins.
 */

/**
 * For each content id seen more than once in one scan, the single path the write phase processes.
 * Copies of one id found in one scan live under one root, so the lowest path is the winner. Ids
 * with a single copy are absent.
 */
internal fun lowestPathPerDuplicatedId(extracted: List<ExtractedSongData>): Map<String, String> {
    val lowest = HashMap<String, String>()
    val duplicated = HashSet<String>()
    for (data in extracted) {
        val id = data.id ?: continue
        val seen = lowest[id]
        if (seen == null) {
            lowest[id] = data.path
        } else {
            duplicated += id
            if (data.path < seen) lowest[id] = data.path
        }
    }
    lowest.keys.retainAll(duplicated)
    return lowest
}

/** What the scan knows about the root it walked, needed to rank the stored path against a scanned one. */
internal class DuplicateCopyContext(
    val scannedRootIsMusic: Boolean,
    val primaryRoot: Uri,
    val otherRoots: List<Uri>,
    /** Paths this scan saw on disk; a stored path in here needs no existence probe. */
    val knownPresentPaths: Set<String>,
    val existenceProbe: DocumentExistenceProbe,
    val rootMembership: suspend (document: Uri, root: Uri) -> Boolean,
)

/** Outcome of comparing a stored path with a scanned path for the same content id. */
internal enum class DuplicateCopyVerdict {
    /** The stored path is unusable (flagged missing or gone), so the scanned file is a relocation. */
    StoredPathUnavailable,

    /** Both copies exist and the scanned one outranks the stored one. */
    ScannedCopyOutranks,

    /** Both copies exist and the stored one outranks (or is) the scanned one. */
    StoredCopyOutranks,
}

internal suspend fun DuplicateCopyContext.compare(existing: SongEntity, scannedPath: String): DuplicateCopyVerdict {
    if (existing.isMissing) return DuplicateCopyVerdict.StoredPathUnavailable
    val stored = existing.filePath
    // Unknown counts as unavailable: a row left on a path nobody can open is worse than moving it.
    val storedPresent = stored in knownPresentPaths ||
        runCatching { existenceProbe.exists(stored.toUri()) }
            .getOrDefault(DocumentExistence.Unknown) == DocumentExistence.Present
    if (!storedPresent) return DuplicateCopyVerdict.StoredPathUnavailable

    val storedIsMusic = storedIsUnderMusicRoot(stored.toUri())
    return when {
        scannedRootIsMusic && !storedIsMusic -> DuplicateCopyVerdict.ScannedCopyOutranks
        !scannedRootIsMusic && storedIsMusic -> DuplicateCopyVerdict.StoredCopyOutranks
        scannedPath < stored -> DuplicateCopyVerdict.ScannedCopyOutranks
        else -> DuplicateCopyVerdict.StoredCopyOutranks
    }
}

private suspend fun DuplicateCopyContext.storedIsUnderMusicRoot(stored: Uri): Boolean = if (scannedRootIsMusic) {
    rootMembership(stored, primaryRoot)
} else {
    otherRoots.any { rootMembership(stored, it) }
}
