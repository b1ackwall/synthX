package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.toDomain
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val TAG = "SongGraduation"

/**
 * Stage-by-stage progress of [graduateSong], reported through its `onStage` callback so a
 * caller can drive a staged progress dialog. The terminal stage — [Done] or [Failed] — is
 * always the last one reported.
 */
sealed class GraduationStage {
    data object Copying : GraduationStage()
    data object Verifying : GraduationStage()
    data object DeletingOriginal : GraduationStage()
    data object UpdatingDatabase : GraduationStage()
    data object Done : GraduationStage()
    data class Failed(val reason: String) : GraduationStage()
}

/** Same terminal outcome as [GraduationStage.Done]/[GraduationStage.Failed], for callers that
 *  only want the end result rather than every intermediate stage. */
sealed class GraduationResult {
    data object Success : GraduationResult()
    data class Failure(val reason: String) : GraduationResult()
}

/** Outcome of graduation's single DB transaction — see [commitGraduatedPath]. */
internal sealed interface GraduationCommit {
    /** Row updated. [song] is the re-read domain Song for the remap notice, if it resolved. */
    data class Updated(val song: Song?) : GraduationCommit

    /** The row no longer exists. The published file was kept — see [reportGraduationCommit]. */
    data object RowGone : GraduationCommit
}

internal const val GRADUATION_RESTORE_IN_PROGRESS_REASON =
    "A backup restore is in progress. Try again after it finishes."

internal const val GRADUATION_FOLDER_CHANGING_REASON =
    "Your library folders are being changed. Try again in a moment."

internal const val GRADUATION_ROW_GONE_REASON =
    "The song was moved into your library folder, but it was removed from the library while " +
        "moving. The file was kept — run a library scan to add it back."

/**
 * Moves a song's file out of wherever it currently lives (typically the New Arrivals tree)
 * into [rootMusicFolderUri] — copy to a temp name, hash-verify, publish, delete the
 * original — then updates the song's DB row (`file_path`, `isStarred = false`). Mirrors
 * [com.v1per.synthx.data.backup.SafBackupPublisher]'s copy-to-temp-name → verify →
 * rename-to-final → best-effort-cleanup-on-failure shape.
 *
 * On any failure before the original is deleted, the original file and the DB row are left
 * completely untouched and any partial/published destination file is best-effort deleted —
 * see the `safeToCleanupDestination` handling below for the one point past which that
 * reverses (once the original is gone, the destination file is the only surviving copy and
 * must never be deleted on a later failure).
 *
 * **Coordination.** The whole operation — not just the DB write — runs:
 *  1. under [FileOperationCoordinator.withSongLock] for [songId], so delete/replace of this song
 *     wait for it, a second graduation of it queues behind it, and a scan skips its row;
 *  2. under one finite [RestoreCoordinator] permit, so a restore drains it (waits for it to
 *     finish) instead of closing Room between the original's deletion and the DB update;
 *  3. holding [FileOperationCoordinator.acquireRoot] usage on both the destination and the
 *     source tree, so a folder change can't release either grant mid-move.
 * That is the acquisition order the coordinator documents: song lock → permit → root usage.
 * The DB write therefore uses the already-held permit rather than taking a nested one — a nested
 * acquire would fail the moment a restore entered preparation, which is exactly the
 * "file moved but library not updated" state this exists to prevent.
 */
suspend fun graduateSong(
    context: Context,
    database: SynthXDatabase,
    songDao: SongDao,
    songId: String,
    rootMusicFolderUri: Uri,
    rootNewArrivalsFolderUri: Uri? = null,
    onStage: suspend (GraduationStage) -> Unit = {},
): GraduationResult = withContext(Dispatchers.IO) {
    FileOperationCoordinator.withSongLock(songId) {
        val permit = try {
            RestoreCoordinator.acquireOperation()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Graduation refused: database admission is closed", e)
            onStage(GraduationStage.Failed(GRADUATION_RESTORE_IN_PROGRESS_REASON))
            return@withSongLock GraduationResult.Failure(GRADUATION_RESTORE_IN_PROGRESS_REASON)
        }
        var commit: GraduationCommit? = null
        var publishedPath: String? = null
        val admittedResult = try {
            graduateAdmitted(
                context,
                database,
                songDao,
                songId,
                rootMusicFolderUri,
                rootNewArrivalsFolderUri,
                onStage,
            ) { c, path ->
                commit = c
                publishedPath = path
            }
        } finally {
            permit.release()
        }
        // Reported after the permit is released, matching FolderScanner's post-commit flush: the
        // remap consumer persists the queue in response, which takes a permit of its own.
        val committed = commit
        if (admittedResult == null && committed != null) {
            reportGraduationCommit(committed, songId, publishedPath, onStage)
        } else {
            admittedResult ?: GraduationResult.Failure("Graduation ended without a result")
        }
    }
}

/**
 * Everything [graduateSong] does while holding the song lock and restore permit. Returns a
 * terminal failure, or null once [onCommitted] has received the DB commit's outcome (which the
 * caller reports after releasing the permit).
 */
private suspend fun graduateAdmitted(
    context: Context,
    database: SynthXDatabase,
    songDao: SongDao,
    songId: String,
    rootMusicFolderUri: Uri,
    rootNewArrivalsFolderUri: Uri?,
    onStage: suspend (GraduationStage) -> Unit,
    onCommitted: (GraduationCommit, String) -> Unit,
): GraduationResult? {
    // Tracks whichever destination DocumentFile currently exists (temp, then published) so a
    // failure can clean it up — except once safeToCleanupDestination flips false, at which
    // point it is the only surviving copy of the file and must be left alone.
    var destinationDoc: DocumentFile? = null
    var safeToCleanupDestination = true
    val rootUsages = mutableListOf<FileOperationCoordinator.RootUsage>()

    suspend fun fail(reason: String): GraduationResult {
        var reportedReason = reason
        if (safeToCleanupDestination) {
            destinationDoc?.let { doc ->
                reportedReason = reasonAfterPublishedCleanup(reportedReason, doc)
            }
        }
        onStage(GraduationStage.Failed(reportedReason))
        return GraduationResult.Failure(reportedReason)
    }

    try {
        val existing = songDao.getSongById(songId)
            ?: return fail("Song no longer exists")

        val sourceUri = runCatching { existing.filePath.toUri() }.getOrNull()
            ?: return fail("Source path is unreadable")

        // Registered before any provider is touched, so a folder change that starts after this
        // point waits for the whole move; one already retiring either tree refuses it here.
        for (uri in listOf(rootMusicFolderUri, sourceUri)) {
            val usage = FileOperationCoordinator.acquireRoot(FileOperationCoordinator.rootKey(uri))
                ?: return fail(GRADUATION_FOLDER_CHANGING_REASON)
            rootUsages += usage
        }

        val source = DocumentFile.fromSingleUri(context, sourceUri)?.takeIf { it.exists() }
            ?: return fail("Source file could not be found")

        val sourcePreflight = GraduationSourcePreflight(
            probe = AndroidAudioDocumentPreflightProbe(context),
            roots = ConfiguredAudioRoots(
                music = rootMusicFolderUri.toString(),
                newArrivals = rootNewArrivalsFolderUri?.toString(),
            ),
        ).check(source.uri.toString())
        if (sourcePreflight is GraduationSourcePreflightResult.Failed) {
            return fail(sourcePreflight.reason)
        }

        // Write-capable, not merely reachable. An audio root granted by an app version that
        // never created files under it holds a read-only SAF grant, and it stays read-only,
        // since a persisted grant never widens on its own. DocumentFile.createFile swallows the resulting
        // SecurityException into a bare null, surfacing as the opaque "Could not create
        // destination file" further down. Checked up front instead, so a permission-scope
        // problem says what to do about it, and that downstream message is left to mean what it
        // actually should: a genuine provider-level refusal.
        val destinationRoot = isRootWritable(context, rootMusicFolderUri)
            ?: return fail(
                // Distinguished deliberately: a readable-but-unwritable root is the user's to
                // fix by re-picking the folder, an unreachable one is not.
                if (isRootReachable(context, rootMusicFolderUri) != null) {
                    "synthX doesn't have write access to your library folder. Reselect it in " +
                        "Settings > Library Management."
                } else {
                    "Library folder is unreachable"
                },
            )

        val displayName = source.name
            ?: return fail("Source file has no name")

        val publishResult = copyVerifyAndPublish(context, source, destinationRoot, displayName, existing.id, onStage)
        val published = when (publishResult) {
            is GraduationPublishResult.Failed -> return fail(publishResult.reason)
            is GraduationPublishResult.Published -> publishResult.document
        }
        destinationDoc = published

        // Stray-row guard: a DB row already pointing at this exact resolved destination path
        // is a different edge case from the same-named-file collision handled above (that one
        // was resolved by picking a free name on disk; this is a *database* row referencing a
        // path with no live document behind it — e.g. a stale/missing row from a since-deleted
        // file whose SAF provider deterministically reuses document ids for the same name).
        // Checked here, against the real published Uri, rather than predicting it before
        // creation — SAF document ids aren't reliably predictable ahead of time across
        // providers, and checking the real Uri still happens before the original is touched or
        // the DB is written, so a clean abort is still possible. Never evicted/reconciled —
        // always a clean abort per spec.
        val strayOccupant = songDao.getSongByPath(published.uri.toString())
        if (strayOccupant != null && strayOccupant.id != existing.id) {
            return fail("A library entry already points at the destination path")
        }

        // From deleting the original through the DB commit is one uninterruptible unit: a
        // cancellation landing between the two (the caller's scope going away) would otherwise
        // leave the file moved and the row still pointing at the deleted original. NonCancellable
        // only protects against coroutine cancellation, not process death — the durable record
        // below is what lets a kill in this exact window be reconciled on the
        // next launch instead of leaving the row silently pointing at a deleted file forever.
        return withContext(NonCancellable) {
            onStage(GraduationStage.DeletingOriginal)
            val cleanupOperationId = UUID.randomUUID().toString()
            // Published path is already known (copy/verify/publish above already ran) — write the
            // record with everything reconciliation will need BEFORE the source is touched.
            database.pendingCleanupDao().insert(
                PendingCleanupEntity(
                    operationId = cleanupOperationId,
                    songId = songId,
                    operationType = PendingCleanupType.GRADUATE.name,
                    filePath = null,
                    publishedPath = published.uri.toString(),
                    phase = PendingCleanupPhase.SourceDeleted.name,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            val deleted = try {
                source.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Original delete threw after verified copy", e)
                false
            }
            if (!deleted) {
                // Original is still there — nothing was actually left in the interrupted state
                // the pending record exists for.
                database.pendingCleanupDao().clear(cleanupOperationId)
                // The copy is verified-good, but the original still exists. Do NOT proceed to the
                // DB update — that would point the library at the new path while the old file (no
                // longer referenced by anything) silently lingers as an orphaned duplicate. Surface
                // this distinctly rather than as a generic failure: the graduated copy is valid,
                // only the original's cleanup needs a retry.
                return@withContext fail(
                    "Copy verified, but the original file could not be removed. Library not " +
                        "updated to avoid a duplicate — try again.",
                )
            }
            // Past this point the destination file is the ONLY surviving copy of this song's
            // audio — a later failure must never delete it.
            safeToCleanupDestination = false

            onStage(GraduationStage.UpdatingDatabase)
            val publishedPath = published.uri.toString()
            try {
                onCommitted(commitGraduatedPath(database, songDao, songId, publishedPath), publishedPath)
                // Row is updated (or confirmedly gone, see GraduationCommit.RowGone) — the
                // source-deleted/row-not-yet-updated window this record tracked is over either way.
                database.pendingCleanupDao().clear(cleanupOperationId)
                null
            } catch (e: Exception) {
                // Deliberately NOT cleared: the row still points at the deleted source, and
                // startup reconciliation is exactly what resolves that on the next launch instead
                // of requiring a manual rescan.
                Log.e(TAG, "Database update failed after verified file move", e)
                onStage(
                    GraduationStage.Failed(
                        "File moved and verified, but the library could not be updated. Run a " +
                            "library scan to pick it up, or try again.",
                    ),
                )
                GraduationResult.Failure("Database update failed")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Graduation failed unexpectedly", e)
        return fail("Something went wrong. Try again.")
    } finally {
        rootUsages.forEach { it.release() }
    }
}

/**
 * Graduation's one DB write, as a single transaction: re-read the row, re-point it, and read the
 * joined domain Song back. Must run under the caller's restore permit and song lock.
 *
 * Re-fetched rather than reusing the row read before the file I/O, and inside the transaction
 * rather than before it, so no other writer's commit can land between the read and the upsert and
 * be overwritten by a stale copy.
 */
internal suspend fun commitGraduatedPath(
    database: SynthXDatabase,
    songDao: SongDao,
    songId: String,
    publishedPath: String,
): GraduationCommit = database.withTransaction {
    val fresh = songDao.getSongById(songId) ?: return@withTransaction GraduationCommit.RowGone
    songDao.upsertSong(
        fresh.copy(
            filePath = publishedPath,
            isStarred = false,
        ),
    )
    // Read back through the same album/artist-joined path the repository maps every other
    // domain Song from, inside this same transaction. A Song assembled by hand from `fresh` alone
    // would be missing artists/album, and the remap consumer replaces the engine's whole
    // cached Song for the slot — so a degraded one here would trade a stale path for stale
    // metadata in the player UI.
    GraduationCommit.Updated(
        songDao.getSongsWithDetailsByIds(listOf(songId))
            .firstOrNull()
            ?.toDomain(songDao.getSongArtistRefs(songId)),
    )
}

/**
 * Turns a landed [GraduationCommit] into graduation's terminal stage and result.
 *
 * [GraduationCommit.RowGone] is a failure, not a success. With delete and replace waiting on
 * the song lock this should be genuinely exceptional — but reading it as "the file moved, so it
 * succeeded" would report Done while leaving an orphaned file for a future scan to silently
 * re-import, undoing a deletion. The file is deliberately *kept* (the row vanishing is no evidence
 * the user wants the audio destroyed); it is logged with its path and the user is told.
 */
internal suspend fun reportGraduationCommit(
    commit: GraduationCommit,
    songId: String,
    publishedPath: String?,
    onStage: suspend (GraduationStage) -> Unit,
): GraduationResult = when (commit) {
    GraduationCommit.RowGone -> {
        Log.w(TAG, "Graduated file orphaned: row $songId vanished before the DB update — kept at $publishedPath")
        onStage(GraduationStage.Failed(GRADUATION_ROW_GONE_REASON))
        GraduationResult.Failure(GRADUATION_ROW_GONE_REASON)
    }

    is GraduationCommit.Updated -> {
        // The live playback engine caches a Song per queue slot and bakes its file_path into
        // every MediaItem it builds; neither re-reads the row. Without this notice the original
        // path — a file graduation just deleted — stays loaded for the rest of the process, and
        // the next forced re-open of it (a long seek, stepping back into it, a window rebuild)
        // fails with no recovery.
        //
        // oldId == newSong.id is correct and deliberate here, not a degenerate case — see
        // SongRemapEvents' KDoc. Graduation is a pure path change; the content hash the id is
        // derived from is unchanged (and verified so), so every queue slot, playlist membership
        // and FK survives under the same id.
        commit.song?.let { SongRemapEvents.notifyRemap(oldId = songId, newSong = it) }
        onStage(GraduationStage.Done)
        GraduationResult.Success
    }
}
