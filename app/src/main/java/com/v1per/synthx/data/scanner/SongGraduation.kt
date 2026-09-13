package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.toDomain
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
 * **Coordination (P2-T1).** The whole operation — not just the DB write — runs:
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
            graduateAdmitted(context, database, songDao, songId, rootMusicFolderUri, onStage) { c, path ->
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
        if (safeToCleanupDestination) {
            destinationDoc?.let { doc ->
                runCatching { if (doc.exists()) doc.delete() }
                    .onFailure { Log.w(TAG, "Could not clean up partial destination file", it) }
            }
        }
        onStage(GraduationStage.Failed(reason))
        return GraduationResult.Failure(reason)
    }

    try {
        val existing = songDao.getSongById(songId)
            ?: return fail("Song no longer exists")

        val sourceUri = runCatching { Uri.parse(existing.filePath) }.getOrNull()
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

        // Graduation writes to *two* trees, not one: it creates the copy under the music root
        // and then deletes the original, which lives under whichever root the song was scanned
        // from — New Arrivals, for every song the Starred list can reach. That delete needs a
        // write grant on that tree just as much as the create needs one on the destination.
        // Probed here rather than left to `source.delete()` below, because by then the verified
        // copy already exists and the failure reads as "try again" — advice that can never work
        // while the grant is the problem. canWrite() is a real provider query (permission plus
        // the document's own FLAG_SUPPORTS_DELETE), so it also catches a file the provider
        // simply refuses to remove.
        if (!runCatching { source.canWrite() }.getOrDefault(false)) {
            return fail(
                "synthX doesn't have write access to the folder this song is in. Reselect " +
                    "your New Arrivals folder in Settings > Library Management.",
            )
        }

        // Write-capable, not merely reachable. Until graduation shipped, nothing ever created a
        // file under an audio root, so both roots used to be held with a read-only SAF grant —
        // and any grant taken back then is still read-only, since a persisted grant never
        // widens on its own. DocumentFile.createFile swallows the resulting
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

        // Same-named-file collision: an explicit, predictable " (1)", " (2)"... suffix rather
        // than relying on provider auto-rename behavior.
        val finalName = resolveCollisionFreeName(destinationRoot, displayName)

        onStage(GraduationStage.Copying)
        val mimeType = source.type ?: "application/octet-stream"
        // Scans never enumerate this name — see isScannableAudioName.
        val tempName = "$GRADUATION_TEMP_PREFIX$finalName"
        val temp = destinationRoot.createFile(mimeType, tempName)
            ?: return fail("Could not create destination file")
        destinationDoc = temp
        if (temp.name != tempName) {
            return fail("Destination provider changed the temporary file name")
        }
        try {
            copyBytes(context, source.uri, temp.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Copy to destination failed", e)
            return fail("Could not copy file to the library folder")
        }

        onStage(GraduationStage.Verifying)
        val copiedId = HashUtils.computeSongId(temp, context)
        if (copiedId == null || copiedId != existing.id) {
            return fail("Copied file failed verification")
        }

        if (!temp.renameTo(finalName)) {
            return fail("Could not publish destination file")
        }
        val published = destinationRoot.findFile(finalName)
            ?: return fail("Published destination file is not visible")
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
        // leave the file moved and the row still pointing at the deleted original.
        return withContext(NonCancellable) {
            onStage(GraduationStage.DeletingOriginal)
            val deleted = try {
                source.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Original delete threw after verified copy", e)
                false
            }
            if (!deleted) {
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
                null
            } catch (e: Exception) {
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
    // would be missing artists/album/palette, and the remap consumer replaces the engine's whole
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
 * [GraduationCommit.RowGone] is a failure, not a success. With delete and replace now waiting on
 * the song lock this should be genuinely exceptional — but the old "the file moved, so it
 * succeeded" reading reported Done while leaving an orphaned file for a future scan to silently
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

/** Plain stream copy — same approach as SafBackupPublisher.copySnapshot. */
private fun copyBytes(context: Context, source: Uri, destination: Uri) {
    val resolver = context.contentResolver
    val output = resolver.openOutputStream(destination, "w")
        ?: throw IllegalStateException("Could not open destination for writing")
    output.use { out ->
        val input = resolver.openInputStream(source)
            ?: throw IllegalStateException("Could not open source for reading")
        input.use { it.copyTo(out) }
    }
}
