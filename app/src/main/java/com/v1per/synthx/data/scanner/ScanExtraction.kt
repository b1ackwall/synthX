package com.v1per.synthx.data.scanner

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.deleteSongArtFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "SynthXScan"

/**
 * How many files [extractScanData] reads at once.
 *
 * Deliberately capped well below the thread count [Dispatchers.IO] would otherwise allow.
 * The ceiling is set by memory, not by CPU: an in-flight extraction can hold the raw
 * embedded-picture byte array *plus* a sampled decode (up to ~2000px square before the
 * last-mile downscale, i.e. ~16MB) *plus* the scaled copy. [AlbumArtExtractor] catches
 * `OutOfMemoryError` per file and degrades to "no art for this song", so over-subscribing
 * here would not crash the scan — it would silently lose album art, which is a far worse
 * outcome than a slightly slower scan. Four concurrent decodes keeps the peak in the tens
 * of megabytes while still removing the great majority of the sequential wall-clock cost;
 * beyond that the SAF/binder round-trips and storage bandwidth become the limit anyway.
 */
private val EXTRACTION_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

/**
 * Which of [FolderScanner]'s five identity-resolution branches a file is *expected* to take.
 *
 * **This is a prediction, not a decision.** It is computed in [extractScanData] against the
 * database as it stands *before* the scan's write transaction opens, and its only job is to
 * answer "does this file need its metadata read?". The write phase re-derives the real branch
 * from live, in-transaction state, because earlier files in the same pass can change the
 * answer — a duplicate-content file collapses onto the row an earlier file just inserted, and
 * a path-collision eviction can delete the very row this prediction was based on. See
 * [ExtractedSongData.metadata] for how the write phase copes when a prediction turns out to
 * have been the wrong one.
 */
internal enum class ScanBranch {
    /** No identity hash could be computed — the file is counted as an error and skipped. */
    HASH_FAILED,

    /** Known content at a new path. Needs no metadata: the stored row is moved, not re-read. */
    PATH_MOVED,

    /** Known content at its known path, previously flagged missing. Needs no metadata. */
    REAPPEARED,

    /** Known content at its known path. Needs tags (no art) for the tag-drift comparison. */
    RETAG_CHECK,

    /** Unknown content. Needs the full read: tags *and* album art. */
    INSERT,
}

/**
 * Everything one audio file's tags and embedded art yield, with none of it yet written.
 *
 * Mirrors the field derivation `SongIngestor.insertNewSong` used to do inline — same title
 * fallback, same year/track parsing, same artist splitting — so a row built from this is
 * byte-for-byte the row the old inline path would have produced.
 *
 * [readOk] distinguishes "the file's tags say this" from "the retriever threw and these are
 * fallbacks". The insert path ignores the difference (it wants the filename fallback either
 * way, exactly as before); the retag path must not, since writing fallbacks over tags that
 * were fine a moment ago would clobber them.
 */
internal data class ExtractedMetadata(
    val readOk: Boolean,
    val title: String,
    /** Split, trimmed and case-insensitively deduped, in tag order. Never empty. */
    val artistNames: List<String>,
    /** Trimmed + lowercased position-0 artist, the key album resolution matches on. */
    val primaryArtistNormalized: String,
    /** Null when the file carried no album tag — resolution is skipped entirely then. */
    val albumTitle: String?,
    val duration: Long,
    val year: Int?,
    val trackNumber: Int?,
    val albumArtUri: String?,
    val albumArtFullUri: String?,
)

/**
 * One file's complete pre-transaction work product: its identity, its predicted branch, and
 * — only when that branch needs it — its extracted metadata and already-written art.
 *
 * [metadata] is null for the three branches that never read tags ([ScanBranch.HASH_FAILED],
 * [ScanBranch.PATH_MOVED], [ScanBranch.REAPPEARED]), matching what the old inline loop did.
 * The write phase treats null as "extract live, right here" rather than as an error, which is
 * what makes a mis-predicted branch safe: the only prediction that can turn into a branch
 * needing metadata is [ScanBranch.PATH_MOVED] becoming an insert (its row got evicted as a
 * path-collision occupant earlier in the same pass), and that falls back to exactly the
 * synchronous extraction the scan used to do for every file.
 */
internal data class ExtractedSongData(
    val file: DocumentFile,
    val path: String,
    /** Null only for [ScanBranch.HASH_FAILED]. */
    val id: String?,
    val branch: ScanBranch,
    val metadata: ExtractedMetadata?,
)

/**
 * Phase 1 of a scan: hash, identify and read every file in [files], concurrently, with no
 * write transaction open.
 *
 * This is where a scan now spends nearly all of its time. It is deliberately kept free of
 * writes — the only database access is the read-only `getSongById` probe that decides whether
 * a file needs reading at all — so it can run [EXTRACTION_PARALLELISM]-wide without holding
 * Room's single transaction thread hostage, and so a cancellation here costs nothing but the
 * work in flight.
 *
 * Structured concurrency is load-bearing in two directions:
 *  - **Cancellation.** [coroutineScope] cancels every outstanding child the moment the caller
 *    is cancelled. Files still queued behind the semaphore never start; the at most
 *    [EXTRACTION_PARALLELISM] files already inside a blocking `MediaMetadataRetriever` call
 *    run to the end of *that file* and no further, since native reads cannot be interrupted.
 *    That bounds a cancelled scan's tail at `EXTRACTION_PARALLELISM` files' work rather than
 *    the one file the old sequential loop cost — a deliberate, small trade.
 *  - **Admission.** [coroutineScope] runs its children as children of the *caller's* job, and
 *    does not return until all of them are done. The caller is inside
 *    `RestoreCoordinator.withDbOperation`'s permit, so no extraction coroutine can outlive
 *    that permit and none of this work can escape `RestoreCoordinator.awaitDrained`.
 *
 * [artWrittenIds] collects the id of every song this phase wrote art files for. Those files
 * land in `filesDir/art/` immediately, before anything is committed, so the caller needs the
 * list to clean them up if the write transaction never commits.
 *
 * [onProgress] is invoked with `(filesCompleted, files.size)` as files finish — in completion
 * order, which is no longer walk order. It is throttled to whole-percent changes and
 * serialized through a mutex, so a `suspend` callback that crosses a process boundary (as
 * `LibraryScanWorker`'s `setProgress`/`setForeground` does) still sees one caller at a time
 * and no more updates than the old sequential loop produced.
 */
internal suspend fun extractScanData(
    context: Context,
    songDao: SongDao,
    ingestor: SongIngestor,
    files: List<DocumentFile>,
    artWrittenIds: MutableSet<String>,
    onProgress: suspend (current: Int, total: Int) -> Unit,
): List<ExtractedSongData> {
    if (files.isEmpty()) return emptyList()

    val total = files.size
    val completed = AtomicInteger(0)
    // Seeded below any real percent so the first completed file always reports.
    val lastReportedPercent = AtomicInteger(Int.MIN_VALUE)
    val progressMutex = Mutex()
    val gate = Semaphore(EXTRACTION_PARALLELISM)
    val startMs = System.currentTimeMillis()

    val extracted = coroutineScope {
        files.map { file ->
            // Dispatchers.IO rather than Dispatchers.Default: every step below is blocking —
            // SAF streams for the hash, native media parsing for the tags — and the bitmap
            // work that genuinely is CPU-bound is already bounded by the semaphore. Running
            // it on Default would occupy the shared CPU pool that the repository's Flow
            // mapping (SongRepositoryImpl.flowOn(Dispatchers.Default)) needs to keep the
            // library UI responsive while a scan runs.
            async(Dispatchers.IO) {
                val data = gate.withPermit {
                    // Checked inside the permit, so a file cancelled while queued is dropped
                    // before it opens a single stream.
                    currentCoroutineContext().ensureActive()
                    extractOneFile(context, songDao, ingestor, file, artWrittenIds)
                }
                // Outside the permit: reporting can block on the progress mutex, and holding
                // an extraction slot while waiting on it would throttle the whole phase.
                reportProgress(completed, lastReportedPercent, progressMutex, total, onProgress)
                data
            }
        }.awaitAll()
    }

    // Broken out by branch because the mix is what explains the number: a first scan is
    // almost all inserts (tags + art decode per file), a rescan of a settled library is
    // almost all retag checks (tags only), and the two cost wildly different amounts.
    Log.d(
        TAG,
        "Scan extraction complete in ${System.currentTimeMillis() - startMs}ms — " +
            "$total files across $EXTRACTION_PARALLELISM workers " +
            "(read=${extracted.count { it.metadata != null }} " +
            "skipped=${extracted.count { it.metadata == null }})",
    )
    return extracted
}

/**
 * Deletes the art files a scan wrote for songs that never reached a committed transaction.
 *
 * [extractScanData] writes `filesDir/art/` files before the write transaction opens, and the
 * write loop's live-extraction fallback writes them inside it — neither is undone by a
 * rollback, since a file delete is not transactional. This is the counterpart: called on the
 * way out of a cancelled or failed scan, when by definition no row exists to own any of them.
 *
 * Best-effort by design. It runs while an exception is already propagating (often a
 * cancellation), so a failure to delete is logged and skipped rather than allowed to displace
 * the real cause. A file left behind is inert — worst case it is re-used, or re-deleted, by
 * the same id on the next scan.
 */
internal fun deleteOrphanedScanArt(context: Context, artWrittenIds: Set<String>) {
    if (artWrittenIds.isEmpty()) return

    try {
        val deleted = deleteSongArtFiles(context, artWrittenIds)
        Log.d(
            TAG,
            "Scan did not commit — deleted $deleted orphaned art file(s) " +
                "from ${artWrittenIds.size} extracted song(s)",
        )
    } catch (e: Exception) {
        // Non-fatal on purpose: this runs while the real failure (usually a cancellation) is
        // already propagating and must not displace it. Anything left behind is inert — the
        // next scan of the same content re-uses or re-deletes it under the same id.
        Log.w(TAG, "Could not clean up orphaned scan art for ${artWrittenIds.size} songs", e)
    }
}

/**
 * The whole of one file's pre-transaction work: identity, branch prediction, and — only if
 * the prediction calls for it — the tag/art read.
 */
private suspend fun extractOneFile(
    context: Context,
    songDao: SongDao,
    ingestor: SongIngestor,
    file: DocumentFile,
    artWrittenIds: MutableSet<String>,
): ExtractedSongData {
    val path = file.uri.toString()

    // Unreadable file: no identity, nothing we can safely store. Same verdict the old inline
    // loop reached, just reached before the transaction rather than inside it.
    val id = HashUtils.computeSongId(file, context)
        ?: return ExtractedSongData(file, path, id = null, branch = ScanBranch.HASH_FAILED, metadata = null)

    currentCoroutineContext().ensureActive()

    // Read-only, non-transactional, and one indexed primary-key lookup — safe to issue from
    // every extraction coroutine at once. Room funnels suspend DAO calls onto its own query
    // dispatcher, so these queue there rather than contending; and because the write
    // transaction has not opened yet, none of them can block on it.
    val existing = songDao.getSongById(id)
    val branch = when {
        existing == null -> ScanBranch.INSERT
        existing.filePath != path -> ScanBranch.PATH_MOVED
        existing.isMissing -> ScanBranch.REAPPEARED
        else -> ScanBranch.RETAG_CHECK
    }

    val metadata = when (branch) {
        // Art is extracted only on the insert path, exactly as before: retagIfChanged has
        // always skipped AlbumArtExtractor deliberately, since decoding and rewriting a
        // bitmap is the cost that path exists to avoid paying when nothing changed.
        ScanBranch.INSERT -> ingestor.extractMetadata(file, id, includeArt = true)
        ScanBranch.RETAG_CHECK -> ingestor.extractMetadata(file, id, includeArt = false)
        ScanBranch.HASH_FAILED, ScanBranch.PATH_MOVED, ScanBranch.REAPPEARED -> null
    }

    // Either art URI being non-null means at least one file was written under this id.
    if (metadata?.albumArtUri != null || metadata?.albumArtFullUri != null) {
        artWrittenIds.add(id)
    }

    return ExtractedSongData(file, path, id, branch, metadata)
}

/**
 * Counts one completed file and emits progress if that crossed a whole-percent boundary.
 *
 * The compare-and-set is what makes concurrent callers safe without a lock on the common
 * path: whichever worker observes the change wins the right to report it, every other worker
 * for the same percent sees its CAS fail and stays silent. Only the winner takes the mutex,
 * so [onProgress] — and therefore `setProgress`/`setForeground` downstream — is never
 * re-entered concurrently, and fires at most ~101 times per scan, matching the whole-percent
 * throttle `LibraryScanWorker` already applies.
 */
private suspend fun reportProgress(
    completed: AtomicInteger,
    lastReportedPercent: AtomicInteger,
    progressMutex: Mutex,
    total: Int,
    onProgress: suspend (current: Int, total: Int) -> Unit,
) {
    val done = completed.incrementAndGet()
    val percent = done * 100 / total
    val previous = lastReportedPercent.get()
    if (percent != previous && lastReportedPercent.compareAndSet(previous, percent)) {
        progressMutex.withLock { onProgress(done, total) }
    }
}
