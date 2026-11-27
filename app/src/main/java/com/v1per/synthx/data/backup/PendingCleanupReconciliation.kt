package com.v1per.synthx.data.backup

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.v1per.synthx.data.db.PendingCleanupDao
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.confirmsGone
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongAudioFile
import com.v1per.synthx.data.isStaleFilePathSentinel
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PendingCleanupReconciliationTracker

private const val TAG = "PendingCleanupRecon"

/**
 * Startup reconciliation for [PendingCleanupEntity] — consumes every record left behind
 * by a destructive operation (delete/replace/graduate) interrupted between its DB commit and its
 * physical completion, most notably a process death, and retries the audio file delete for a
 * replace whose old file survived its first attempt.
 *
 * Runs inside [DatabaseStartupGate]'s own migration-gate Room access (see
 * [SynthXDatabase.getInstanceForMigrationGate]), before the process-wide gate opens — so no
 * repository or ViewModel code can ever observe a half-reconciled record.
 *
 * [schedulePlaylistExport] is called at most once per pass, when the pass finished a delete's
 * after-commit work. It must not open Room itself: the gate is still closed while this runs.
 */
internal object PendingCleanupReconciliation {

    suspend fun reconcile(context: Context, database: SynthXDatabase, schedulePlaylistExport: () -> Unit) {
        val dao = database.pendingCleanupDao()
        val records = dao.getAll()
        if (records.isEmpty()) return
        Log.i(TAG, "Reconciling ${records.size} leftover pending cleanup record(s)")

        var reconciled = 0
        var exportNeeded = false
        for (record in records) {
            try {
                when (PendingCleanupType.valueOf(record.operationType)) {
                    PendingCleanupType.DELETE -> if (reconcileDelete(context, dao, record)) exportNeeded = true
                    PendingCleanupType.GRADUATE -> reconcileGraduate(context, database, dao, record)
                }
                reconciled++
            } catch (e: Exception) {
                // One unparseable/broken record must never block startup or the rest of the sweep
                // — it stays behind and is retried on the next launch.
                Log.e(TAG, "Failed to reconcile pending cleanup ${record.operationId}", e)
            }
        }
        if (reconciled > 0) {
            // Counts every record this pass acted on, including a delete whose file is still on
            // disk and whose record is kept for the next launch.
            PendingCleanupReconciliationTracker.recordReconciliation(context, reconciled)
        }
        if (exportNeeded) {
            try {
                schedulePlaylistExport()
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule the playlist export after reconciliation", e)
            }
        }
    }

    /**
     * A delete/replace record. The row is already gone by construction.
     *
     * [PendingCleanupPhase.Committed]: the in-app cleanup never ran, so this finishes all of it:
     * the audio file, the art, the destructive-action count and (through the return value) the
     * playlist export. [PendingCleanupPhase.AudioFileRemains]: the in-app cleanup ran and only the
     * audio file is left, so only the delete is retried.
     *
     * The record is cleared only once the file is gone (deleted, confirmed absent, or the path is
     * a stale sentinel naming no file). Otherwise it is kept as [PendingCleanupPhase.AudioFileRemains],
     * so scans keep skipping the path and the next launch retries. No live playback exists this
     * early at startup, so there is no `SongDeletionEvents` notice to redo here.
     *
     * Returns true when the after-commit work was finished here, so a playlist export is needed.
     */
    private suspend fun reconcileDelete(
        context: Context,
        dao: PendingCleanupDao,
        record: PendingCleanupEntity,
    ): Boolean {
        val filePath = record.filePath
        val fileGone = when {
            filePath == null || isStaleFilePathSentinel(filePath) -> true
            else -> deleteSongAudioFile(context, filePath).confirmsGone
        }
        val firstAttempt = record.phase != PendingCleanupPhase.AudioFileRemains.name
        if (firstAttempt) {
            deleteSongArtFile(context, record.songId)
            DestructiveActionTracker.recordDestructiveAction(context)
        }
        if (fileGone) {
            dao.clear(record.operationId)
        } else if (firstAttempt) {
            dao.updatePhase(record.operationId, PendingCleanupPhase.AudioFileRemains.name)
        }
        return firstAttempt
    }

    /**
     * [com.v1per.synthx.data.db.PendingCleanupPhase.SourceDeleted]: the source file is already
     * gone and the row may still point at it. Re-points the row to the published destination when
     * that file is actually there; if the destination is ALSO gone (or unreachable), the row can
     * no longer honestly claim either path, so it is flagged missing — the same treatment as any
     * other song whose file has disappeared (see `VerifyLibraryWorker`) — rather than left
     * silently pointing at a deleted file.
     */
    private suspend fun reconcileGraduate(
        context: Context,
        database: SynthXDatabase,
        dao: PendingCleanupDao,
        record: PendingCleanupEntity,
    ) {
        val publishedPath = record.publishedPath
        if (publishedPath == null) {
            dao.clear(record.operationId)
            return
        }
        val songDao = database.songDao()
        database.withTransaction {
            val song = songDao.getSongById(record.songId)
            when {
                song == null -> Unit

                // Row already gone — nothing left to re-point.
                song.filePath == publishedPath -> Unit

                // Already reconciled.
                publishedDocumentExists(context, publishedPath) ->
                    songDao.upsertSong(song.copy(filePath = publishedPath, isStarred = false))

                else -> songDao.upsertSong(
                    song.copy(
                        isMissing = true,
                        missingSince = song.missingSince ?: System.currentTimeMillis(),
                    ),
                )
            }
            dao.clear(record.operationId)
        }
    }

    private fun publishedDocumentExists(context: Context, publishedPath: String): Boolean {
        val uri = runCatching { publishedPath.toUri() }.getOrNull() ?: return false
        return try {
            DocumentFile.fromSingleUri(context, uri)?.exists() == true
        } catch (e: Exception) {
            false
        }
    }
}
