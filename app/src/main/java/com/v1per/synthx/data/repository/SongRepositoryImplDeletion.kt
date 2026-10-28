package com.v1per.synthx.data.repository

import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.db.queryInBatches
import android.util.Log
import androidx.room.withTransaction
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.SongDeletionAcknowledgment
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.preferences.DestructiveActionTracker
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

// SongRepositoryImpl's song deletion and its after-commit cleanup.

private const val TAG = "SongRepositoryImpl"

/** How long [SongRepositoryImpl]'s deletion handoff barrier waits before proceeding anyway. */
private const val DELETION_HANDOFF_TIMEOUT_MS = 1_500L

internal suspend fun runPostCommitStep(
    step: String,
    logFailure: (String, Throwable) -> Unit = { failedStep, error ->
        Log.e(TAG, "Post-commit step failed: $failedStep", error)
    },
    block: suspend () -> Unit,
): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    try {
        logFailure(step, e)
    } catch (loggingCancellation: CancellationException) {
        throw loggingCancellation
    } catch (_: Throwable) {
        // Reporting a cleanup failure must not turn it back into a propagated post-commit failure.
    }
    false
}

internal fun songDeletionOutcome(audioOutcome: AudioFileDeletion, cleanupUncertain: Boolean): SongDeletionOutcome =
    when {
        audioOutcome == AudioFileDeletion.Failed -> SongDeletionOutcome.FileRemained

        audioOutcome == AudioFileDeletion.Unknown || cleanupUncertain ->
            SongDeletionOutcome.CleanupUncertain

        else -> SongDeletionOutcome.Deleted
    }

internal fun includePlaylistExportOutcome(
    deletionOutcome: SongDeletionOutcome,
    exportSucceeded: Boolean,
): SongDeletionOutcome = if (!exportSucceeded && deletionOutcome == SongDeletionOutcome.Deleted) {
    SongDeletionOutcome.CleanupUncertain
} else {
    deletionOutcome
}

/**
 * Permanently removes a song. Its song_artists and playlist_songs rows go with it via
 * FK CASCADE — nothing here deletes them explicitly.
 *
 * The album-art re-point + row delete (via [deleteSongRowWithArtRepoint]) share one
 * transaction; the art file, the audio file, and the live-playback notice all follow only
 * after it commits, since none of the three can be rolled back alongside the DB. Same shared
 * cleanup FolderScanner's internal evictions use, so both deletion paths stay in sync.
 *
 * The `file_path` is read inside the same transaction that deletes the row — afterwards there
 * is nothing left to read it from.
 */
internal suspend fun SongRepositoryImpl.deleteSongInternal(id: String): SongDeletionOutcome =
    withContext(Dispatchers.IO) {
        // Backup-then-act: a failed backup (no folder configured) must not block the
        // delete the user explicitly asked for.
        // Backup waits for finite operations to drain, so it must precede our own permit.
        backupManager.createBackup(BackupTier.Auto, BackupReason.DELETE_SONG)

        // Song lock after the backup, never before — see FileOperationCoordinator's acquisition
        // order. Waits out an in-flight graduation of this exact song rather than deleting the
        // row out from under it, which would leave its graduated copy orphaned for a scan to
        // re-import. Held through the file cleanup so a scan can't re-claim the row mid-delete.
        FileOperationCoordinator.withSongLock(id) {
            // Recheck admission: restore may have begun while the backup was running.
            dbOperation {
                // The permit is already held so restore cannot close Room while the current row is
                // read, but no transaction is opened until the live SAF preflight has passed (or
                // returned an intentionally non-blocking gone/ambiguous result).
                val beforeDelete = songDao.getSongById(id)
                if (beforeDelete != null) {
                    when (
                        val preflight = destructiveFileCheck(
                            beforeDelete.filePath,
                            beforeDelete.id,
                        )
                    ) {
                        is DestructiveFilePreflightResult.Blocking ->
                            return@dbOperation SongDeletionOutcome.Blocked(preflight)

                        DestructiveFilePreflightResult.Passed,
                        DestructiveFilePreflightResult.SourceGone,
                        DestructiveFilePreflightResult.Ambiguous,
                        -> Unit
                    }
                }
                val operationId = UUID.randomUUID().toString()
                val filePath = database.withTransaction {
                    val existing = songDao.getSongById(id)
                    deleteSongRowWithArtRepoint(songDao, albumDao, id)
                    // Durable BEFORE the row is gone: same transaction as the
                    // row delete, so a crash right after commit still leaves a record behind for
                    // startup reconciliation to finish the file cleanup this call never got to.
                    pendingCleanupDao.insert(
                        PendingCleanupEntity(
                            operationId = operationId,
                            songId = id,
                            operationType = PendingCleanupType.DELETE.name,
                            filePath = existing?.filePath,
                            publishedPath = null,
                            phase = PendingCleanupPhase.Committed.name,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    existing?.filePath
                }
                val deletionOutcome = runPostCommitCleanup(operationId, id, filePath)
                // Known accepted limitation: if restore closes admission before the nested
                // pending-cleanup clear in runPostCommitCleanup, that clear throws and this
                // enqueue is not reached; the durable record remains for startup reconciliation.
                val exportSucceeded = runPostCommitStep("schedule playlist export") {
                    schedulePlaylistExport()
                }
                includePlaylistExportOutcome(deletionOutcome, exportSucceeded)
            }
        }
    }

internal suspend fun SongRepositoryImpl.previewDeleteSongInternal(id: String): DeleteSongPreview = dbOperation {
    database.withTransaction {
        val playlistIds = playlistDao.getPlaylistIdsForSong(id).distinct()
        if (playlistIds.isEmpty()) return@withTransaction DeleteSongPreview(emptyList())
        val namesById = playlistIds.queryInBatches(playlistDao::getPlaylistsByIds)
            .associate { it.id to it.name }
        val countsById = playlistIds.queryInBatches(playlistDao::getPlaylistMemberCounts)
            .associate { it.playlistId to it.memberCount }
        DeleteSongPreview(
            playlistIds.mapNotNull { playlistId ->
                namesById[playlistId]?.let { name ->
                    DeleteSongPlaylistImpact(
                        name = name,
                        willBecomeEmpty = countsById[playlistId] == 1,
                    )
                }
            }.sortedBy { it.name.lowercase() },
        )
    }
}

/**
 * Runs [finishSongDeletion] on [repositoryScope] instead of inline, and joins it — so a
 * caller whose own coroutine gets cancelled (e.g. `IssuesViewModel`'s
 * `NavBackStackEntry` going away mid-delete) only loses the *wait*, not the cleanup itself:
 * [repositoryScope] is not a structured child of the caller's job, so cancelling the caller
 * cannot cancel work already launched on it. The normal (non-cancelled) case is unaffected —
 * this still returns the real [SongDeletionOutcome] once cleanup finishes.
 *
 * [operationId]'s [PendingCleanupEntity] is cleared once [finishSongDeletion] returns,
 * regardless of whether individual steps inside it failed — those ordinary failures are
 * already surfaced honestly via [SongDeletionOutcome] (`FileRemained`/`CleanupUncertain`),
 * which retrying via startup reconciliation would not change. The record exists to protect
 * against *interruption* (this function never returning at all, i.e. process death), not
 * against a provider that permanently refuses a delete.
 */
internal suspend fun SongRepositoryImpl.runPostCommitCleanup(
    operationId: String,
    id: String,
    filePath: String?,
): SongDeletionOutcome {
    val cleanup = repositoryScope.async { finishSongDeletion(id, filePath) }
    val outcome = cleanup.await()
    dbOperation { pendingCleanupDao.clear(operationId) }
    return outcome
}

/**
 * The after-commit half of a complete delete, shared by [deleteSong] and [replaceSong].
 *
 * [replaceSong] does not call [deleteSong] because that one opens its own
 * `database.withTransaction`, and [replaceSong]'s membership transplant plus row delete have
 * to be a single atomic unit — so the transaction-scoped part ([deleteSongRowWithArtRepoint])
 * and this out-of-transaction part are called separately by each caller, which also keeps the
 * backup-before-permit ordering intact on both paths.
 *
 * **Must be called with the deleting transaction already committed.** Order within it is
 * deliberate: live playback is told first so it can skip off the song before its file
 * disappears underneath the player, then the files go, then the destructive-action counter
 * records that a backup is now stale. Only ever called via [runPostCommitCleanup], which is
 * what actually protects this from being cut short by the caller's scope ending.
 */
private suspend fun SongRepositoryImpl.finishSongDeletion(id: String, filePath: String?): SongDeletionOutcome {
    var cleanupUncertain = false

    // Handoff barrier: notifyDeleted's suspending emit only guarantees the notice is enqueued
    // into a buffered SharedFlow, not that any collector has run yet, let alone that the
    // resulting controller commands (skip/stop/remove) have reached the player. Without this, the
    // file delete right below can race a still-open handle. The waiter is registered before the
    // emit, because an engine can acknowledge inside it and an acknowledgment with no waiter is
    // dropped. Bounded rather than an unconditional await: with no live playback engine there is
    // nothing to hand off to (register() returns null), and even with one, an engine wedged for
    // some unrelated reason must not block a deletion the user explicitly asked for forever. The
    // waiter is released whichever way the wait ends.
    val ack = runCatching { SongDeletionAcknowledgment.register(id) }.getOrNull()
    try {
        if (!runPostCommitStep("notify song deletion") { SongDeletionEvents.notifyDeleted(id) }) {
            cleanupUncertain = true
        } else if (ack != null) {
            val acknowledged = withTimeoutOrNull(DELETION_HANDOFF_TIMEOUT_MS) { ack.await() }
            if (acknowledged == null) {
                Log.w(TAG, "Timed out waiting for playback to release song $id before deleting its file")
            }
        }
    } finally {
        if (ack != null) SongDeletionAcknowledgment.unregister(id, ack)
    }

    var fileOutcome = if (filePath == null) {
        AudioFileDeletion.ConfirmedAbsent
    } else {
        AudioFileDeletion.Unknown
    }
    if (filePath != null && !runPostCommitStep("delete audio file") {
            fileOutcome = audioFileDelete(filePath)
        }
    ) {
        cleanupUncertain = true
    }

    if (!runPostCommitStep("delete cached song art") { deleteSongArtFile(context, id) }) {
        cleanupUncertain = true
    }
    if (!runPostCommitStep("record destructive action") {
            DestructiveActionTracker.recordDestructiveAction(context)
        }
    ) {
        cleanupUncertain = true
    }
    return songDeletionOutcome(fileOutcome, cleanupUncertain)
}
