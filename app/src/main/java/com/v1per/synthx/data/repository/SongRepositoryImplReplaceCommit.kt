package com.v1per.synthx.data.repository

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.confirmsGone
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.isStaleFilePathSentinel
import com.v1per.synthx.data.scanner.GraduationResult
import com.v1per.synthx.data.scanner.graduateSong
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException

// SongRepositoryImpl's replace-song work after the transaction commits.

private const val TAG = "SongRepositoryImpl"

/** What the locked half of [replaceSongInternal] produced. */
internal sealed interface LockedReplace {
    /** Nothing was written; [result] says why. */
    data class Rejected(val result: ReplaceSongResult) : LockedReplace

    /** The transaction committed and the old song's cleanup has run. */
    data class Committed(val cleanup: ReplaceCleanup, val graduateReplacement: Boolean) : LockedReplace
}

/** The old song's after-commit cleanup, as [finishCommittedReplace] reports it. */
internal data class ReplaceCleanup(val oldAudioFile: OldAudioFile, val followUpIncomplete: Boolean)

/**
 * The old song's after-commit cleanup for a replace: the playback notice, the audio file, art and
 * the destructive-action count (all in finishSongDeletion), then the cleanup record. Never throws
 * except for cancellation: the replace has committed, so a failure here is reported in the
 * result, never as a failed replace.
 *
 * Unlike delete, the record is kept while the old file may still be on disk. It moves to
 * [PendingCleanupPhase.AudioFileRemains], so scans skip the path and startup reconciliation
 * retries the delete. A record that cannot be cleared or moved stays as it is, for startup
 * reconciliation to finish.
 */
internal suspend fun SongRepositoryImpl.finishCommittedReplace(
    oldSongId: String,
    operationId: String,
    oldFilePath: String,
): ReplaceCleanup {
    val cleanup = try {
        awaitPostCommitCleanup(oldSongId, oldFilePath)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.e(TAG, "Replace cleanup failed after the commit", e)
        PostCommitCleanup(AudioFileDeletion.Unknown, followUpIncomplete = true)
    }
    // A stale sentinel path names no file, so there is nothing left on disk to wait for.
    val audio = if (isStaleFilePathSentinel(oldFilePath)) AudioFileDeletion.ConfirmedAbsent else cleanup.audio
    val recordSettled = settleReplaceCleanupRecord(operationId, fileGone = audio.confirmsGone)
    return ReplaceCleanup(
        oldAudioFile = when (audio) {
            AudioFileDeletion.Deleted, AudioFileDeletion.ConfirmedAbsent -> OldAudioFile.Removed
            AudioFileDeletion.Failed -> OldAudioFile.StillPresent
            AudioFileDeletion.Unknown -> OldAudioFile.Unconfirmed
        },
        followUpIncomplete = cleanup.followUpIncomplete || !recordSettled,
    )
}

/** Clears the record once the old file is gone, or marks it as waiting on the file. */
private suspend fun SongRepositoryImpl.settleReplaceCleanupRecord(operationId: String, fileGone: Boolean): Boolean =
    try {
        dbOperation {
            if (fileGone) {
                pendingCleanupDao.clear(operationId)
            } else {
                pendingCleanupDao.updatePhase(operationId, PendingCleanupPhase.AudioFileRemains.name)
            }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.e(TAG, "Replace cleanup record left for startup reconciliation", e)
        false
    }

/**
 * Runs once the old song's lock is released: graduates a New Arrivals replacement, then asks
 * for a playlist export, so the export sees the replacement's final path. Never throws except
 * for cancellation.
 */
internal suspend fun SongRepositoryImpl.completeCommittedReplace(
    newSongId: String,
    committed: LockedReplace.Committed,
    onStage: (ReplaceSongStage) -> Unit,
): ReplaceSongResult.Success {
    val move = if (committed.graduateReplacement) {
        moveReplacementIntoMusic(newSongId, onStage)
    } else {
        ReplacementMove.NotNeeded
    }
    val exportScheduled = runPostCommitStep("schedule playlist export") { schedulePlaylistExport() }
    return ReplaceSongResult.Success(
        oldAudioFile = committed.cleanup.oldAudioFile,
        followUpIncomplete = committed.cleanup.followUpIncomplete || !exportScheduled,
        replacementMove = move,
    )
}

private suspend fun SongRepositoryImpl.moveReplacementIntoMusic(
    newSongId: String,
    onStage: (ReplaceSongStage) -> Unit,
): ReplacementMove = try {
    onStage(ReplaceSongStage.MovingReplacement)
    if (graduateReplacement(newSongId)) ReplacementMove.Moved else ReplacementMove.Incomplete
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Log.e(TAG, "Moving the replacement into the Music folder failed", e)
    ReplacementMove.Incomplete
}

/**
 * Production graduation for a New Arrivals replacement: [graduateSong] with the configured
 * folders, read the way the song actions sheet's "Remove from Starred" reads them. False when no
 * Music folder is set or graduation does not succeed; graduation's own reason is logged.
 */
internal suspend fun graduateReplacementIntoMusic(
    context: Context,
    database: SynthXDatabase,
    songDao: SongDao,
    songId: String,
): Boolean {
    val preferences = PreferencesManager(context)
    val musicRoot = preferences.musicFolderUri?.let { runCatching { it.toUri() }.getOrNull() }
    if (musicRoot == null) {
        Log.w(TAG, "Replacement not moved: no Music folder is set")
        return false
    }
    val newArrivalsRoot = preferences.newArrivalsFolderUri?.let { runCatching { it.toUri() }.getOrNull() }
    return when (val result = graduateSong(context, database, songDao, songId, musicRoot, newArrivalsRoot)) {
        GraduationResult.Success -> true

        is GraduationResult.Failure -> {
            // Graduation's reasons are fixed app messages, not paths.
            Log.w(TAG, "Replacement not moved: ${result.reason}")
            false
        }
    }
}
