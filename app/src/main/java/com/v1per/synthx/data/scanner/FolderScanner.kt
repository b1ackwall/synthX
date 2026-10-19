package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.SongArtistDao
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.FileOperationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class FolderScanner internal constructor(
    internal val context: Context,
    internal val database: SynthXDatabase,
    internal val songDao: SongDao,
    internal val artistDao: ArtistDao,
    internal val albumDao: AlbumDao,
    private val songArtistDao: SongArtistDao,
    internal val playlistExportManager: PlaylistExportManager,
    internal val scanBodyForTest: (suspend () -> ScanResult)? = null,
    internal val rootResolver: (Uri) -> DocumentFile? = { isRootReachable(context, it) },
    internal val directoryQuery: DirectoryChildrenQuery = AndroidDirectoryChildrenQuery(context),
    private val documentExistenceProbe: DocumentExistenceProbe = AndroidDocumentExistenceProbe(context),
    private val rootMembership: suspend (Uri, Uri) -> Boolean = { document, root ->
        RootNesting.isUnderRoot(context, document, root)
    },
) {

    /**
     * Per-file metadata extraction and row writing. Constructed once and reused for every
     * file — it holds no per-scan state, which is also what makes its extraction half safe
     * to call from many coroutines at once. Its *writing* methods run inside the scan's own
     * Room transaction; see [SongIngestor]'s transaction contract.
     */
    internal val ingestor = SongIngestor(context, songDao, artistDao, albumDao, songArtistDao)

    /**
     * Scans [folderUri] and reconciles the library against it.
     *
     * Concurrency is not guarded here — LibraryScanWorker enqueues under a unique
     * work name, so only one scan can be in flight per process.
     *
     * The scan runs in two phases. **Extraction** ([extractScanData]) hashes, identifies and
     * reads every file, concurrently and with no transaction open — nearly all of a scan's
     * wall-clock cost, and the only part with no cross-file ordering dependency. **Writing**
     * then replays those results into Room strictly in walk order inside one transaction,
     * where the ordering *is* load-bearing (album/artist reuse, duplicate collapse, path
     * eviction). The split is purely for performance: the write phase behaves exactly as if
     * it read each file inline.
     *
     * [onProgress] receives `(current, total)` where `current` is the number of audio
     * files fully extracted so far — in completion order, which in the concurrent extraction
     * phase is not walk order. Everything outside that phase has no
     * meaningful denominator — the file-system walk before the file list exists, and the
     * write, missing-detection and orphan-cleanup passes afterwards — and reports
     * [PROGRESS_INDETERMINATE] for **both** arguments. Callers should treat `total <= 0` as
     * "show a spinner, not a bar".
     *
     * The whole mutation phase runs in one Room transaction, so a cancelled or failed
     * scan rolls back rather than leaving the library half-reconciled. Album art files are
     * the one thing a rollback cannot undo, so a scan that does not commit deletes the art
     * it wrote on its way out (see `deleteOrphanedScanArt`). Cancellation is checked once
     * per file in both phases; extraction's blocking native reads cannot be interrupted
     * mid-file, so a cancelled scan finishes at most the handful of files already in flight.
     *
     * [reconcileMissing] controls the post-loop missing-detection pass, which compares
     * *every* song in the library against the set of files found on disk. Pass `false` when
     * the caller can't account for the whole library — scanning the new-arrivals tree, say —
     * so `is_missing` is left completely untouched and [ScanResult.missing] reports 0.
     *
     * [otherRootUris] are the *other* configured audio roots, i.e. every root except
     * [folderUri] itself. They are enumerated (paths only — no hashing, no metadata, no
     * ingestion) purely so their files join the found-path set that missing-detection
     * reconciles against. Without them a song living only under another root would be flagged
     * missing by every scan of this one. Ignored entirely when [reconcileMissing] is `false`.
     * This parameter is plain data: the scanner deliberately knows nothing about preferences,
     * so the caller decides what the roots are.
     *
     * If any root in [otherRootUris] can't be enumerated — grant revoked, provider error,
     * unresolvable URI — missing-detection is skipped for the whole scan rather than run
     * against a short path set that would wrongly flag every song under that root. The scan
     * still succeeds; [ScanResult.missingReconciled] reports `false`.
     *
     * [isNewArrivalsRoot] identifies [folderUri] as the New Arrivals tree rather than the
     * main library root. It drives `isStarred` (the `in_inbox` column) for every song this
     * scan inserts or newly claims a path for — a song currently found under New Arrivals is
     * starred, one found under the main root is not. Defaults to `false`, the main-root case.
     */
    suspend fun scan(
        folderUri: Uri,
        reconcileMissing: Boolean = true,
        otherRootUris: List<Uri> = emptyList(),
        isNewArrivalsRoot: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = FolderScannerAdmission.direct {
        scanAlreadyAdmitted(folderUri, reconcileMissing, otherRootUris, isNewArrivalsRoot, onProgress)
    }

    /** Caller already owns RestoreCoordinator admission for the complete scan transaction. */
    internal suspend fun scanAlreadyAdmitted(
        folderUri: Uri,
        reconcileMissing: Boolean = true,
        otherRootUris: List<Uri> = emptyList(),
        isNewArrivalsRoot: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult =
        scanAlreadyAdmittedInternal(folderUri, reconcileMissing, otherRootUris, isNewArrivalsRoot, onProgress)

    /**
     * Cheap existence sweep over the whole library: flags songs whose stored `file_path`
     * no longer resolves. No hashing, no metadata, no folder walk — one `exists()` probe
     * per row, committed row by row rather than in a transaction, so it can run on launch
     * without blocking anything.
     *
     * Deliberately one-way. A song that resolves is left exactly as it is, including one
     * already flagged missing — un-flagging is [scan]'s job, since only a real scan can
     * confirm the file's content still matches the row's identity hash.
     *
     * [configuredRoots] are every currently configured audio root (primary + any
     * others). Each is probed for reachability exactly once before any per-song check
     * runs — a per-file `exists()` probe can't tell "the whole tree is unreachable right
     * now" from "this one file is gone", so a song under a root that fails its
     * reachability check is skipped entirely rather than risking a false missing-flag.
     *
     * @return the number newly flagged plus any root-scoped uncertainty encountered.
     */
    suspend fun verify(configuredRoots: List<ConfiguredAudioRoot>): VerifyResult = RestoreCoordinator.withDbOperation {
        verifyAlreadyAdmitted(configuredRoots)
    }

    /** Caller already owns admission for the complete verification sweep. */
    internal suspend fun verifyAlreadyAdmitted(
        configuredRoots: List<ConfiguredAudioRoot>,
    ): VerifyResult = withContext(Dispatchers.IO) {
        val degradations = linkedSetOf<ScanDegradation>()
        val reachableRoots = configuredRoots.filter { configured ->
            (rootResolver(configured.uri) != null).also { reachable ->
                if (!reachable) {
                    degradations += ScanDegradation(
                        configured.role,
                        ScanDegradationReason.RootUnreachable,
                    )
                }
            }
        }

        var newlyMissing = 0
        for (song in songDao.getAllSongsOnce()) {
            currentCoroutineContext().ensureActive()
            if (song.isMissing) continue // already flagged — nothing this pass can add

            // A song whose path doesn't resolve to any currently-reachable root — either
            // the root it lives under is unreachable, or its stored path is unparseable —
            // is left untouched this pass rather than treated as gone. Treating an
            // unparseable path as "gone" would be the same unreachable-vs-deleted
            // conflation this whole pass exists to avoid, so it gets the same
            // "can't tell, don't touch" handling.
            val songUri = runCatching { song.filePath.toUri() }.getOrNull() ?: continue
            val owningRoot = reachableRoots.firstOrNull { root ->
                rootMembership(songUri, root.uri)
            } ?: continue

            when (documentExistenceProbe.exists(songUri)) {
                DocumentExistence.Present -> continue
                DocumentExistence.Unknown -> {
                    degradations += ScanDegradation(
                        owningRoot.role,
                        ScanDegradationReason.VerificationIncomplete,
                    )
                    continue
                }
                DocumentExistence.ConfirmedAbsent -> Unit
            }

            // The snapshot above can be older than a graduation/delete that has since moved or
            // removed this song: its old path failing to resolve proves nothing on its own. Skip
            // a song another operation holds right now; otherwise, holding its lease, re-read the
            // row and judge the path it has *now*.
            val lease = FileOperationCoordinator.tryAcquireSong(song.id) ?: continue
            try {
                val current = songDao.getSongById(song.id) ?: continue
                if (current.isMissing) continue
                if (current.filePath != song.filePath) {
                    val currentUri = runCatching { current.filePath.toUri() }.getOrNull() ?: continue
                    val currentRoot = reachableRoots.firstOrNull { root ->
                        rootMembership(currentUri, root.uri)
                    } ?: continue
                    when (documentExistenceProbe.exists(currentUri)) {
                        DocumentExistence.Present -> continue
                        DocumentExistence.Unknown -> {
                            degradations += ScanDegradation(
                                currentRoot.role,
                                ScanDegradationReason.VerificationIncomplete,
                            )
                            continue
                        }
                        DocumentExistence.ConfirmedAbsent -> Unit
                    }
                }
                songDao.markMissing(song.id, System.currentTimeMillis())
                newlyMissing++
            } finally {
                lease.release()
            }
        }
        VerifyResult(newlyMissing, degradations)
    }

    companion object {
        /** Sentinel for both `onProgress` arguments while no file count is known yet. */
        const val PROGRESS_INDETERMINATE = -1
    }
}

/** The two real FolderScanner entrypoint ownership modes, kept small enough for JVM proof. */
internal object FolderScannerAdmission {
    suspend fun <T> direct(block: suspend () -> T): T =
        RestoreCoordinator.withDbOperation(block)

    suspend fun <T> alreadyAdmitted(block: suspend () -> T): T = block()
}
