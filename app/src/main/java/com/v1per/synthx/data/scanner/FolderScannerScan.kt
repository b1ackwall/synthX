package com.v1per.synthx.data.scanner

import android.net.Uri
import android.util.Log
import com.v1per.synthx.data.db.SongDao
import androidx.room.withTransaction
import com.v1per.synthx.data.SongRemap
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.withScanRowClaims
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.toDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.v1per.synthx.data.scanner.FolderScanner.Companion.PROGRESS_INDETERMINATE

private const val TAG = "SynthXScan"

/**
 * Re-reads [id] through the same album/artist-joined path every other domain [Song] is mapped
 * from, for a remap notice — mirrors [commitGraduatedPath]'s identical re-read. A hand-assembled
 * Song from just the entity would be missing artists/album, degrading the engine's whole
 * cached Song for the slot rather than only fixing what actually changed.
 */
private suspend fun SongDao.getDomainSongById(id: String): Song? =
    getSongsWithDetailsByIds(listOf(id)).firstOrNull()?.toDomain(getSongArtistRefs(id))

/** Body of [FolderScanner.scanAlreadyAdmitted]; the class keeps a one-line delegator. */
internal suspend fun FolderScanner.scanAlreadyAdmittedInternal(
    folderUri: Uri,
    reconcileMissing: Boolean,
    otherRootUris: List<Uri>,
    isNewArrivalsRoot: Boolean,
    onProgress: suspend (current: Int, total: Int) -> Unit,
): ScanResult {
    scanBodyForTest?.let { body ->
        return FolderScannerAdmission.alreadyAdmitted(body)
    }
    // Row claims span the whole body: the observation they open must predate the walk, so a
    // graduation/delete that lands anywhere between reading a file and committing its row is
    // seen at commit time, and their leases are released only once the commit is over.
    return FolderScannerAdmission.alreadyAdmitted {
        withContext(Dispatchers.IO) {
            withScanRowClaims { claims ->
                val scanStartMs = System.currentTimeMillis()
                onProgress(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE)

                // Cleanup: delete file:// art files left in cacheDir; art lives in
                // filesDir/art/, so these are orphans. No-op once drained.
                context.cacheDir
                    .listFiles { file -> file.name.startsWith("art_") && file.name.endsWith(".png") }
                    ?.forEach { it.delete() }

                // Reachability precondition on the primary root itself — same check applied to
                // every *other* configured root by collectOtherRootPaths. Without it, a revoked
                // grant / ejected volume / transient provider error is indistinguishable from a
                // genuinely empty root: listFiles() swallows the provider error and returns an
                // empty array, so an unreachable primary root would otherwise walk as "found
                // nothing" and flag the entire library missing in the reconciliation pass below.
                val primaryRole = if (isNewArrivalsRoot) KnownAudioRoot.NewArrivals else KnownAudioRoot.Music
                val root = rootResolver(folderUri)
                    ?: return@withContext ScanResult(
                        added = 0,
                        updated = 0,
                        missing = 0,
                        unchanged = 0,
                        missingReconciled = false,
                        degradations = setOf(
                            ScanDegradation(primaryRole, ScanDegradationReason.RootUnreachable),
                        ),
                    ).also {
                        Log.w(TAG, "Skipping scan: primary root is unreachable — $folderUri")
                    }

                // File-system walk stays outside the transaction — it is the slowest phase and
                // touches no DB state.
                val walkStartMs = System.currentTimeMillis()
                var primaryWalkOk = true
                val primaryWalk = try {
                    collectAudioFiles(root, directoryQuery)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Same all-or-nothing handling as collectOtherRootPaths: a mid-walk provider
                    // failure gets no partial credit — treat it as nothing found rather than
                    // reconciling against whatever happened to be collected before it broke.
                    Log.w(TAG, "Skipping missing-detection: failed to walk primary root $folderUri", e)
                    primaryWalkOk = false
                    AudioWalkResult(emptyList(), complete = false)
                }
                val audioFiles = primaryWalk.files
                primaryWalkOk = primaryWalkOk && primaryWalk.complete
                val degradations = linkedSetOf<ScanDegradation>()
                if (!primaryWalk.complete) {
                    degradations += ScanDegradation(primaryRole, ScanDegradationReason.WalkIncomplete)
                }
                val foundPaths = audioFiles.mapTo(mutableSetOf()) { it.uri.toString() }

                // Secondary roots, also before the transaction opens: each is hundreds of binder
                // round-trips, and holding a Room transaction across them would block every other
                // writer for the duration. Enumeration only — these files are not ingested here.
                if (reconcileMissing && primaryWalkOk) {
                    val otherRole = if (primaryRole == KnownAudioRoot.Music) {
                        KnownAudioRoot.NewArrivals
                    } else {
                        KnownAudioRoot.Music
                    }
                    degradations += collectOtherRootPaths(
                        context = context,
                        otherRootUris = otherRootUris,
                        into = foundPaths,
                        root = otherRole,
                        rootResolver = rootResolver,
                        directoryQuery = directoryQuery,
                    )
                }
                val reconcile = reconcileMissing && primaryWalkOk && degradations.isEmpty()
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
                // commits — see the loop after withTransaction. Emitting from inside the loop
                // would let a consumer (ExoPlayerEngine.songsBySlotId) adopt a newSong.id
                // that later turns out to never have been persisted, if the transaction rolls back
                // for an unrelated reason later in the same pass.
                val pendingRemaps = mutableListOf<SongRemap>()

                // Deletion notices for songs hard-deleted mid-transaction as a path-collision occupant
                // — same post-commit-only emission rule as pendingRemaps above, and for
                // the same reason: a rollback after the eviction must not have already told a live
                // playback engine the song is gone.
                val pendingDeletions = mutableListOf<String>()

                // Every song id that had art files written for it before anything was committed —
                // filled concurrently by the extraction phase, and appended to by the write loop's
                // live-extraction fallback. Art files are the one part of a scan a rollback cannot
                // undo, so this set is what keeps a cancelled or failed scan from leaving orphans in
                // filesDir/art/: if the transaction never commits, every id here is cleaned up; if it
                // does, each id is reconciled against the committed rows like any other.
                val artWrittenIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

                // Ids the write loop actually inserted a row for. Their art is owned by a committed
                // row by definition, so the post-commit reconciliation can skip them outright rather
                // than paying a redundant lookup for every song on a first scan of a whole library.
                val insertedIds = mutableSetOf<String>()

                var retagged = 0

                val result = try {
                    // Phase 1 — hash, identify and read every file, concurrently, with no
                    // transaction open. This is where a scan spends nearly all of its time, and
                    // it is the part with no order dependency at all: nothing here writes, so no
                    // file's outcome can affect another's.
                    val extracted = extractScanData(
                        context = context,
                        songDao = songDao,
                        ingestor = ingestor,
                        files = audioFiles,
                        artWrittenIds = artWrittenIds,
                        onProgress = onProgress,
                    )

                    // No per-file denominator from here on — back to indeterminate. The write phase
                    // below reads no files, so it gets no progress bar of its own, and the
                    // missing-detection and orphan-cleanup passes after it have no denominator.
                    onProgress(PROGRESS_INDETERMINATE, PROGRESS_INDETERMINATE)

                    val txnStartMs = System.currentTimeMillis()
                    val scanResult = database.withTransaction {
                        var added = 0
                        var updated = 0
                        var unchanged = 0
                        var deleted = 0
                        var hashMismatchFlagged = 0
                        var errors = 0

                        // Byte-identical copies share one id; the row follows one deterministic copy.
                        val duplicateWinners = lowestPathPerDuplicatedId(extracted)
                        val duplicateCopies = DuplicateCopyContext(
                            scannedRootIsMusic = !isNewArrivalsRoot,
                            primaryRoot = folderUri,
                            otherRoots = otherRootUris,
                            knownPresentPaths = foundPaths,
                            existenceProbe = documentExistenceProbe,
                            rootMembership = rootMembership,
                        )

                        // Phase 2 — strictly in walk order, strictly sequential. Every ordering
                        // dependency here is load-bearing:
                        // album rows are minted by whichever song reaches them first and keep that
                        // song's cover, later files reuse the artists earlier ones created, duplicate
                        // content collapses onto the row the first copy inserted, and a path collision
                        // evicts whoever currently holds the path.
                        for (data in extracted) {
                            // Cooperative cancellation. Cheap per file because the reading is
                            // already done, but the loop is long enough to abandon early.
                            currentCoroutineContext().ensureActive()

                            val path = data.path
                            // Unreadable file: no identity, nothing we can safely store
                            val id = data.id
                            if (id == null) {
                                errors++
                                continue
                            }

                            // A losing copy of content found twice in this scan: the winner is processed.
                            if (duplicateWinners[id]?.let { it != path } == true) {
                                unchanged++
                                continue
                            }

                            // Another operation holds this song, or mutated it after this scan began —
                            // this file's extracted data may already be stale. Leave the row for the next
                            // scan rather than blocking on, or overwriting, that operation.
                            if (!claims.claim(id)) continue

                            // Re-read rather than trusting the extraction phase's branch prediction.
                            // That prediction was made against the pre-scan database, and existed
                            // only to decide whether the file needed reading. By the time a file is
                            // reached here, an earlier file in this same loop may already have
                            // inserted a row under this id (two byte-identical files), moved this row
                            // to another path, or evicted it as a path-collision occupant. Deriving
                            // the branch from live transaction state is what keeps every outcome
                            // identical to the pre-split scan.
                            val existing = songDao.getSongById(id)
                            if (existing != null) {
                                when {
                                    existing.filePath != path -> {
                                        // The stored copy still exists and outranks this one: leave the row.
                                        val verdict = duplicateCopies.compare(existing, path)
                                        if (verdict == DuplicateCopyVerdict.StoredCopyOutranks) {
                                            unchanged++
                                            continue
                                        }
                                        // Check if another song already occupies this path — remove it
                                        // first to avoid UNIQUE constraint violation on file_path index
                                        val occupant = songDao.getSongByPath(path)
                                        if (occupant != null && occupant.id != existing.id) {
                                            if (!claims.claim(occupant.id)) continue
                                            deleteSongRowWithArtRepoint(songDao, albumDao, occupant.id)
                                            pendingArtCleanup.add(occupant.id)
                                            // The evicted occupant's row is hard-deleted right above with
                                            // no remap (it has no successor row — it's simply gone), so a
                                            // live playback engine still holding one of its queue slots
                                            // needs the deletion notice, not a remap.
                                            // Deferred to the post-commit flush alongside pendingRemaps,
                                            // for the identical rollback-safety reason documented there.
                                            pendingDeletions.add(occupant.id)
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
                                                isStarred = if (verdict == DuplicateCopyVerdict.ScannedCopyOutranks) {
                                                    // Both copies exist, so nothing moved: keep the user's star.
                                                    existing.isStarred
                                                } else {
                                                    isNewArrivalsRoot
                                                },
                                            ),
                                        )
                                        // A live playback engine still caches this id's OLD path baked
                                        // into a MediaItem — without this remap it never learns the path
                                        // moved. oldId == the
                                        // row's own id: the content hash is unchanged, only file_path
                                        // moved, exactly like graduation's same-id case — see
                                        // SongRemapEvents' KDoc for why that is legitimate, not degenerate.
                                        songDao.getDomainSongById(existing.id)?.let { fresh ->
                                            pendingRemaps.add(SongRemap(existing.id, fresh))
                                        }
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
                                    // That re-read already happened in phase 1 (tags only, no art, as
                                    // this path has always done); all that is left here is comparing it
                                    // against the stored row.
                                    else -> if (ingestor.retagIfChanged(data.file, existing, data.metadata)) {
                                        // A currently-playing/queued occurrence of this id has the OLD
                                        // title/artist baked into its cached Song and displayed MediaItem
                                        // metadata — same-id remap is exactly the
                                        // refresh mechanism graduation's own same-id case already uses.
                                        songDao.getDomainSongById(existing.id)?.let { fresh ->
                                            pendingRemaps.add(SongRemap(existing.id, fresh))
                                        }
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
                            val staleRow = songDao.getSongByPath(path)
                            if (staleRow != null && !claims.claim(staleRow.id)) continue
                            val staleId = staleRow?.let { stale ->
                                songDao.markMissing(stale.id, System.currentTimeMillis())
                                songDao.clearFilePathForRetag(stale.id)
                                hashMismatchFlagged++
                                stale.id
                            }

                            // Null metadata here means phase 1 predicted a branch that needed no read
                            // and live state disagreed — the only way that happens is a row evicted as
                            // a path-collision occupant earlier in this same loop. insertNewSong then
                            // reads the file right here, which also
                            // means it writes art inside the transaction; register the id so a later
                            // rollback cleans that art up alongside phase 1's.
                            if (data.metadata == null) artWrittenIds.add(id)

                            val newSong = ingestor.insertNewSong(
                                data.file,
                                id,
                                path,
                                isStarred = isNewArrivalsRoot,
                                extracted = data.metadata,
                            )
                            insertedIds.add(id)
                            // If the replaced song is the one currently loaded in the player, its
                            // in-memory Song (title/art) would otherwise go stale — see SongRemapEvents.
                            // Not emitted here: queued and flushed after the transaction commits, below.
                            if (staleId != null) {
                                pendingRemaps.add(SongRemap(staleId, newSong))
                            }
                            added++
                        }

                        var missing = 0
                        if (reconcile) {
                            val now = System.currentTimeMillis()
                            for (song in songDao.getAllSongsOnce()) {
                                if (song.filePath !in foundPaths) {
                                    // A graduation committed after the walk snapshotted foundPaths moved
                                    // this song to a path the walk never saw — not missing, just newer
                                    // than this scan. Same for anything currently locked.
                                    if (!claims.claim(song.id)) continue
                                    if (!song.isMissing) {
                                        songDao.markMissing(song.id, now)
                                    }
                                    // Deliberately no playback event here (locked policy: leave
                                    // an already-loaded/playing song alone when it goes missing
                                    // mid-session; only prevent it from being added to FUTURE fresh
                                    // queues, which PlaybackViewModel.requestPlayQueue already does by
                                    // filtering isMissing songs before they ever reach the engine). A
                                    // currently-playing missing song fails naturally and audibly the next
                                    // time the engine actually tries to open its file (onPlayerError),
                                    // which is a real, user-visible signal — auto-skipping it here instead
                                    // would silently truncate whatever the user is listening to mid-track
                                    // for a condition (the file being gone) that scan/verify sweeps are
                                    // not synchronized with playback's own timing at all.
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
                            deferred = claims.deferred,
                            degradations = degradations,
                        )
                    }
                    Log.d(
                        TAG,
                        "Scan transaction complete in ${System.currentTimeMillis() - txnStartMs}ms — " +
                            "added=${scanResult.added} updated=${scanResult.updated} (retagged=$retagged) " +
                            "unchanged=${scanResult.unchanged} missing=${scanResult.missing} " +
                            "deferred=${scanResult.deferred}",
                    )
                    scanResult
                } catch (t: Throwable) {
                    // Reached only when nothing was committed: either the extraction phase was
                    // cancelled/failed before the transaction ever opened, or the transaction itself
                    // rolled back. Either way every art file this scan wrote is an orphan, and it is
                    // the one artifact the rollback cannot take back. Best-effort and non-fatal — it
                    // must never replace the real cause on the way out.
                    deleteOrphanedScanArt(context, artWrittenIds)
                    throw t
                }

                // Only after the transaction commits — a file delete cannot be rolled back
                // alongside the DB the way the row deletes above can.
                //
                // Re-check each id against the now-committed DB before deleting its art: song ids
                // are content-hash derived, so an id evicted earlier in this same scan (path-collision
                // occupant eviction, above) can be legitimately re-claimed by a later file in the same
                // pass with byte-identical content — that re-insert already committed fresh art under
                // this same id. Deleting unconditionally here would strip that just-written art out
                // from under the resurrected row. A genuinely, permanently evicted id has no row left
                // and is cleaned up.
                //
                // Extraction-phase art joins the same reconciliation, minus the ids that actually
                // became rows: phase 1 writes art before it can know whether the row will really be
                // inserted, and a file it read as new can still collapse onto an existing row by the
                // time the write loop reaches it. One rule decides both cases — an id with a committed
                // row keeps its art, an id without one never became a song.
                val artToReconcile = LinkedHashSet(pendingArtCleanup)
                artWrittenIds.filterNotTo(artToReconcile) { it in insertedIds }
                artToReconcile.forEach { id ->
                    if (songDao.getSongById(id) == null) {
                        deleteSongArtFile(context, id)
                    }
                }

                // Only after the transaction commits — see pendingRemaps' declaration above. A
                // consumer acting on one of these is guaranteed that the replacement row it
                // names exists.
                pendingRemaps.forEach { remap ->
                    SongRemapEvents.notifyRemap(remap.oldId, remap.newSong)
                }

                // Only after the transaction commits — same rollback-safety reason as pendingRemaps
                // above: a live engine must never be told a song is gone if the eviction that deleted
                // its row turns out not to have actually committed.
                pendingDeletions.forEach { deletedId ->
                    SongDeletionEvents.notifyDeleted(deletedId)
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
            }
        }
    }
}
