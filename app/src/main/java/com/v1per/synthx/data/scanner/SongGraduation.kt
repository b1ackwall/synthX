package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.SongRemapEvents
import com.v1per.synthx.data.backup.BackupHashing
import com.v1per.synthx.data.backup.ByteDigest
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.toDomain
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
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

/** Result of the source-side grant and delete-capability check that precedes every copy. */
internal sealed interface GraduationSourcePreflightResult {
    data object Passed : GraduationSourcePreflightResult
    data class Failed(val reason: String) : GraduationSourcePreflightResult
}

/**
 * Graduation-specific destructive preflight. Source reachability is checked by the caller first,
 * so a missing/read-revoked source keeps its distinct message; this check then resolves the
 * configured owner, validates that root's write grant, and asks for delete support specifically.
 */
internal class GraduationSourcePreflight(
    private val probe: AudioDocumentPreflightProbe,
    private val roots: ConfiguredAudioRoots,
) {
    suspend fun check(documentUri: String): GraduationSourcePreflightResult {
        val owner = resolveOwningAudioRoot(probe, documentUri, roots)
            ?: return GraduationSourcePreflightResult.Failed(
                "This song's file is outside your configured Music and New Arrivals folders.",
            )
        val rootName = when (owner.role) {
            KnownAudioRoot.Music -> "Music"
            KnownAudioRoot.NewArrivals -> "New Arrivals"
        }
        when (probe.rootWriteState(owner.uri)) {
            RootWriteState.GrantNotWritable -> return GraduationSourcePreflightResult.Failed(
                "synthX doesn't have write access to your $rootName folder. Reselect it in " +
                    "Settings > Library Management. Nothing was copied.",
            )
            RootWriteState.Ambiguous -> return GraduationSourcePreflightResult.Failed(
                "synthX couldn't verify write access to your $rootName folder. Nothing was copied.",
            )
            RootWriteState.Writable -> Unit
        }
        return when (probe.supportsDelete(documentUri)) {
            true -> GraduationSourcePreflightResult.Passed
            false -> GraduationSourcePreflightResult.Failed(
                "The file in your $rootName folder can't be deleted by its storage provider. " +
                    "Nothing was copied.",
            )
            null -> GraduationSourcePreflightResult.Failed(
                "synthX couldn't confirm that the file in your $rootName folder can be deleted. " +
                    "Nothing was copied.",
            )
        }
    }
}

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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Graduation failed unexpectedly", e)
        return fail("Something went wrong. Try again.")
    } finally {
        rootUsages.forEach { it.release() }
    }
}

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
