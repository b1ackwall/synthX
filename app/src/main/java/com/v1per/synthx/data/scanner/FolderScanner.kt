package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.SongArtistDao
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SynthXDatabase
import androidx.room.withTransaction
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.SongRemap
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanResult(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val unchanged: Int,
    /**
     * Whether the missing-detection pass actually ran. `false` means `is_missing` was left
     * entirely untouched this scan — either it was not requested, or a configured root could
     * not be enumerated and reconciling against a partial path set would have wrongly flagged
     * songs. Distinguishes that from a pass that ran and legitimately found [missing] == 0.
     */
    val missingReconciled: Boolean = true,
    /** Rows hard-deleted this scan — path-collision occupant eviction only (Site A). */
    val deleted: Int = 0,
    /**
     * Rows flagged missing this scan because their path was reused by different content
     * (Site B, hash-mismatch) — counted separately from [missing] (Site C's mass
     * reconciliation flag) since they come from different triggers.
     */
    val hashMismatchFlagged: Int = 0,
    /**
     * Files skipped this scan because no identity hash could be computed for them —
     * the head read failed, or the tail read failed even after [HashUtils]'s internal
     * retry. Distinct from [missing]: these files were seen on disk but couldn't be
     * read, so they are left untouched in the library rather than flagged missing.
     */
    val errors: Int = 0,
) {
    /**
     * Combined count of everything this scan changed destructively or flagged away:
     * hard deletes plus both missing-flag mechanisms. Used to gauge whether a single
     * scan pass affected an unusually large slice of the library.
     */
    val totalImpact: Int get() = deleted + hashMismatchFlagged + missing
}

class FolderScanner internal constructor(
    private val context: Context,
    private val database: SynthXDatabase,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val songArtistDao: SongArtistDao,
    private val playlistExportManager: PlaylistExportManager,
    private val scanBodyForTest: (suspend () -> ScanResult)? = null,
) {

    /**
     * Per-file metadata extraction and row writing. Constructed once and reused for every
     * file — it holds no per-scan state. Its methods run inside the scan's own Room
     * transaction; see [SongIngestor]'s transaction contract.
     */
    private val ingestor = SongIngestor(context, songDao, artistDao, albumDao, songArtistDao)

    /**
     * Scans [folderUri] and reconciles the library against it.
     *
     * Concurrency is no longer guarded here — LibraryScanWorker enqueues under a unique
     * work name, so only one scan can be in flight per process.
     *
     * [onProgress] receives `(current, total)` where `current` is the number of audio
     * files fully processed so far. Two phases have no meaningful denominator — the
     * file-system walk before the file list exists, and the missing-detection plus
     * orphan-cleanup passes afterwards — and report [PROGRESS_INDETERMINATE] for **both**
     * arguments. Callers should treat `total <= 0` as "show a spinner, not a bar".
     *
     * The whole mutation phase runs in one Room transaction, so a cancelled or failed
     * scan rolls back rather than leaving the library half-reconciled. Cancellation is
     * checked once per file, so it takes effect within one file's work.
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
     * starred, one found under the main root is not. Defaults to `false` so every existing
     * caller (main-root scans, tests) keeps its current behaviour unless it opts in.
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
    ): ScanResult {
        scanBodyForTest?.let { body ->
            return FolderScannerAdmission.alreadyAdmitted(body)
        }
        return FolderScannerAdmission.alreadyAdmitted { withContext(Dispatchers.IO) {
        val scanStartMs = System.currentTimeMillis()
        onProgress(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE)

        // Cleanup: delete old file:// art files from cacheDir. Art now lives in
        // filesDir/art/; these are orphans from before that move. No-op once drained.
        context.cacheDir
            .listFiles { file -> file.name.startsWith("art_") && file.name.endsWith(".png") }
            ?.forEach { it.delete() }

        // Reachability precondition on the primary root itself — same check applied to
        // every *other* configured root by collectOtherRootPaths. Without it, a revoked
        // grant / ejected volume / transient provider error is indistinguishable from a
        // genuinely empty root: listFiles() swallows the provider error and returns an
        // empty array, so an unreachable primary root would otherwise walk as "found
        // nothing" and flag the entire library missing in the reconciliation pass below.
        val root = isRootReachable(context, folderUri)
            ?: return@withContext ScanResult(
                added = 0,
                updated = 0,
                missing = 0,
                unchanged = 0,
                missingReconciled = false,
            ).also {
                Log.w(TAG, "Skipping scan: primary root is unreachable — $folderUri")
            }

        // File-system walk stays outside the transaction — it is the slowest phase and
        // touches no DB state.
        val walkStartMs = System.currentTimeMillis()
        var primaryWalkOk = true
        val audioFiles = try {
            collectAudioFiles(root)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same all-or-nothing handling as collectOtherRootPaths: a mid-walk provider
            // failure gets no partial credit — treat it as nothing found rather than
            // reconciling against whatever happened to be collected before it broke.
            Log.w(TAG, "Skipping missing-detection: failed to walk primary root $folderUri", e)
            primaryWalkOk = false
            emptyList()
        }
        val foundPaths = audioFiles.mapTo(mutableSetOf()) { it.uri.toString() }

        // Secondary roots, also before the transaction opens: each is hundreds of binder
        // round-trips, and holding a Room transaction across them would block every other
        // writer for the duration. Enumeration only — these files are not ingested here.
        val reconcile = if (reconcileMissing && primaryWalkOk) {
            collectOtherRootPaths(context, otherRootUris, into = foundPaths)
        } else {
            false
        }
        Log.d(
            TAG,
            "Scan walk complete in ${System.currentTimeMillis() - walkStartMs}ms — " +
                "${audioFiles.size} files found under $folderUri",
        )

        // Song ids evicted mid-transaction by the two paths below — their art files are
        // deleted only after the transaction commits (see the loop after withTransaction),
        // matching SongRepositoryImpl.deleteSong's commit-then-delete-file ordering.
        val pendingArtCleanup = mutableListOf<String>()

        // Remap notices accumulated mid-transaction, emitted only after the transaction
        // commits — see the loop after withTransaction. Emitting from inside the loop (as
        // this used to) would let a consumer (ExoPlayerEngine.songsById) adopt a newSong.id
        // that later turns out to never have been persisted, if the transaction rolls back
        // for an unrelated reason later in the same pass.
        val pendingRemaps = mutableListOf<SongRemap>()

        var retagged = 0
        val txnStartMs = System.currentTimeMillis()
        val result = database.withTransaction {
            var added = 0
            var updated = 0
            var unchanged = 0
            var deleted = 0
            var hashMismatchFlagged = 0
            var errors = 0

            for ((index, file) in audioFiles.withIndex()) {
                // Cooperative cancellation: the per-file work below is mostly blocking
                // I/O, so without this a cancelled scan would run to the end of the list.
                currentCoroutineContext().ensureActive()
                onProgress(index, audioFiles.size)

                val path = file.uri.toString()
                // Unreadable file: no identity, nothing we can safely store
                val id = HashUtils.computeSongId(file, context)
                if (id == null) {
                    errors++
                    continue
                }

                val existing = songDao.getSongById(id)
                if (existing != null) {
                    when {
                        existing.filePath != path -> {
                            // Check if another song already occupies this path — remove it
                            // first to avoid UNIQUE constraint violation on file_path index
                            val occupant = songDao.getSongByPath(path)
                            if (occupant != null && occupant.id != existing.id) {
                                deleteSongRowWithArtRepoint(songDao, albumDao, occupant.id)
                                pendingArtCleanup.add(occupant.id)
                                deleted++
                            }
                            songDao.upsertSong(
                                existing.copy(
                                    filePath = path,
                                    isMissing = false,
                                    missingSince = null,
                                    // Re-derived from the root being scanned rather than
                                    // carried over — this branch fires whenever the same
                                    // content shows up at a new path, including a file
                                    // moving between the main root and New Arrivals, and
                                    // the flag must reflect where it lives *now*.
                                    isStarred = isNewArrivalsRoot,
                                )
                            )
                            updated++
                        }
                        existing.isMissing -> {
                            // Same path, previously flagged missing — the file is back
                            songDao.markFound(id)
                            updated++
                        }
                        // Same path, same hash — the byte ranges the id is built from
                        // (first/last 64KB + size) didn't move, but an ID3 edit that lands
                        // outside those ranges (or reuses the same padding) wouldn't touch
                        // them either. A cheap tag re-read catches that case without paying
                        // for a full metadata extraction + resolution on every untouched file.
                        else -> if (ingestor.retagIfChanged(file, existing)) {
                            retagged++
                            updated++
                        } else {
                            unchanged++
                        }
                    }
                    continue
                }

                // Same path, different hash: the file content changed. The old row is
                // flagged missing rather than deleted — playlist_songs (and everything
                // else) cascade-deletes on songs.id, so a hard delete here would silently
                // wipe the old row's playlist memberships and starred state. Its path is
                // blanked so the new row can claim it (file_path is UNIQUE); its art is
                // left alone since the row — and its legitimate ownership of that art —
                // still exists. No SongRepositoryImpl mutation method runs here to trigger
                // the usual scheduleExport() call — one is requested after the transaction
                // commits instead (see below), since scheduling it from in here risks the
                // debounced export firing and reading the database before this transaction
                // commits, capturing a stale pre-scan snapshot.
                val staleId = songDao.getSongByPath(path)?.let { stale ->
                    songDao.markMissing(stale.id, System.currentTimeMillis())
                    songDao.clearFilePathForRetag(stale.id)
                    hashMismatchFlagged++
                    stale.id
                }

                val newSong = ingestor.insertNewSong(file, id, path, isStarred = isNewArrivalsRoot)
                // If the replaced song is the one currently loaded in the player, its
                // in-memory Song (title/art) would otherwise go stale — see SongRemapEvents.
                // Not emitted here: queued and flushed after the transaction commits, below.
                if (staleId != null) {
                    pendingRemaps.add(SongRemap(staleId, newSong))
                }
                added++
            }

            // No per-file denominator from here on — back to indeterminate.
            onProgress(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE)

            var missing = 0
            if (reconcile) {
                val now = System.currentTimeMillis()
                for (song in songDao.getAllSongsOnce()) {
                    if (song.filePath !in foundPaths) {
                        if (!song.isMissing) {
                            songDao.markMissing(song.id, now)
                        }
                        missing++
                    }
                }
            }

            // Clean up artist and album rows that no longer have any songs referencing them
            artistDao.deleteOrphanArtists()
            albumDao.deleteOrphanAlbums()

            ScanResult(
                added = added,
                updated = updated,
                missing = missing,
                unchanged = unchanged,
                missingReconciled = reconcile,
                deleted = deleted,
                hashMismatchFlagged = hashMismatchFlagged,
                errors = errors,
            )
        }
        Log.d(
            TAG,
            "Scan transaction complete in ${System.currentTimeMillis() - txnStartMs}ms — " +
                "added=${result.added} updated=${result.updated} (retagged=$retagged) " +
                "unchanged=${result.unchanged} missing=${result.missing}",
        )

        // Only after the transaction commits — a file delete cannot be rolled back
        // alongside the DB the way the row deletes above can.
        //
        // Re-check each id against the now-committed DB before deleting its art: song ids
        // are content-hash derived, so an id evicted earlier in this same scan (path-collision
        // occupant eviction, above) can be legitimately re-claimed by a later file in the same
        // pass with byte-identical content — that re-insert already committed fresh art under
        // this same id. Deleting unconditionally here would strip that just-written art out
        // from under the resurrected row. A genuinely, permanently evicted id has no row left
        // and still gets cleaned up as before.
        pendingArtCleanup.forEach { id ->
            if (songDao.getSongById(id) == null) {
                deleteSongArtFile(context, id)
            }
        }

        // Only after the transaction commits — see pendingRemaps' declaration above. A
        // consumer acting on one of these is now guaranteed the replacement row it names
        // actually exists.
        pendingRemaps.forEach { remap ->
            SongRemapEvents.notifyRemap(remap.oldId, remap.newSong)
        }

        // Only after the transaction commits — scheduleExport() is fire-and-forget and its
        // debounced write can fire before an in-transaction call's changes are committed,
        // capturing a stale pre-scan snapshot. Gated on hashMismatchFlagged since that's the
        // only path this scan takes that doesn't already route through a SongRepositoryImpl
        // mutation method (which schedules its own export on commit).
        if (result.hashMismatchFlagged > 0) {
            playlistExportManager.scheduleExport()
        }

        Log.d(TAG, "Scan complete in ${System.currentTimeMillis() - scanStartMs}ms for $folderUri")
            result
        } }
    }

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
     * [configuredRootUris] are every currently configured audio root (primary + any
     * others). Each is probed for reachability exactly once before any per-song check
     * runs — a per-file `exists()` probe can't tell "the whole tree is unreachable right
     * now" from "this one file is gone", so a song under a root that fails its
     * reachability check is skipped entirely rather than risking a false missing-flag.
     *
     * @return the number of songs newly flagged missing by this pass.
     */
    suspend fun verify(configuredRootUris: List<Uri>): Int = RestoreCoordinator.withDbOperation {
        verifyAlreadyAdmitted(configuredRootUris)
    }

    /** Caller already owns admission for the complete verification sweep. */
    internal suspend fun verifyAlreadyAdmitted(configuredRootUris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val reachableRoots = configuredRootUris.filter { isRootReachable(context, it) != null }

        var newlyMissing = 0
        for (song in songDao.getAllSongsOnce()) {
            currentCoroutineContext().ensureActive()
            if (song.isMissing) continue // already flagged — nothing this pass can add

            // A song whose path doesn't resolve to any currently-reachable root — either
            // the root it lives under is unreachable, or its stored path is unparseable —
            // is left untouched this pass rather than treated as gone. Unparseable used to
            // be treated as "gone" here; that was the same unreachable-vs-deleted
            // conflation this whole pass now exists to avoid, so it's folded into the same
            // "can't tell, don't touch" handling.
            val songUri = runCatching { Uri.parse(song.filePath) }.getOrNull() ?: continue
            val underReachableRoot = reachableRoots.any { root ->
                RootNesting.isUnderRoot(context, songUri, root)
            }
            if (!underReachableRoot) continue

            val exists = try {
                DocumentFile.fromSingleUri(context, songUri)?.exists() == true
            } catch (e: Exception) {
                false
            }

            if (!exists) {
                songDao.markMissing(song.id, System.currentTimeMillis())
                newlyMissing++
            }
        }
        newlyMissing
    }

    companion object {
        private const val TAG = "SynthXScan"

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
