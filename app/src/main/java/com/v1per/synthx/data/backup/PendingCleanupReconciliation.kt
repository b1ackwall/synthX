package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.v1per.synthx.data.db.PendingCleanupDao
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongAudioFile
import com.v1per.synthx.preferences.PendingCleanupReconciliationTracker

private const val TAG = "PendingCleanupRecon"

/**
 * Startup reconciliation for [PendingCleanupEntity] (P3-T1) — consumes every record left behind
 * by a destructive operation (delete/replace/graduate) interrupted between its DB commit and its
 * physical completion, most notably a process death.
 *
 * Runs inside [DatabaseStartupGate]'s own migration-gate Room access (see
 * [SynthXDatabase.getInstanceForMigrationGate]), before the process-wide gate opens — so no
 * repository or ViewModel code can ever observe a half-reconciled record.
 */
internal object PendingCleanupReconciliation {

    suspend fun reconcile(context: Context, database: SynthXDatabase) {
        val dao = database.pendingCleanupDao()
        val records = dao.getAll()
        if (records.isEmpty()) return
        Log.i(TAG, "Reconciling ${records.size} leftover pending cleanup record(s)")

        var reconciled = 0
        for (record in records) {
            try {
                when (PendingCleanupType.valueOf(record.operationType)) {
                    PendingCleanupType.DELETE -> reconcileDelete(context, dao, record)
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
            PendingCleanupReconciliationTracker.recordReconciliation(context, reconciled)
        }
    }

    /**
     * [com.v1per.synthx.data.db.PendingCleanupPhase.Committed]: the row is already gone by
     * construction — finish whichever file-cleanup step never ran. No live playback exists this
     * early at startup, so there is no `SongDeletionEvents` notice to redo here (unlike
     * `finishSongDeletion`'s in-app path).
     */
    private suspend fun reconcileDelete(context: Context, dao: PendingCleanupDao, record: PendingCleanupEntity) {
        if (record.filePath != null) {
            deleteSongAudioFile(context, record.filePath)
        }
        deleteSongArtFile(context, record.songId)
        dao.clear(record.operationId)
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
                song == null -> Unit // Row already gone — nothing left to re-point.
                song.filePath == publishedPath -> Unit // Already reconciled.
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
        val uri = runCatching { Uri.parse(publishedPath) }.getOrNull() ?: return false
        return try {
            DocumentFile.fromSingleUri(context, uri)?.exists() == true
        } catch (e: Exception) {
            false
        }
    }
}
