package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingCleanupDao {

    /** Caller must already be inside the transaction that makes the operation's DB change. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingCleanupEntity)

    @Query("DELETE FROM pending_cleanups WHERE operation_id = :operationId")
    suspend fun clear(operationId: String)

    /** Startup reconciliation's entry point — every record left over from an interrupted run. */
    @Query("SELECT * FROM pending_cleanups")
    suspend fun getAll(): List<PendingCleanupEntity>

    /** Moves one record to another [PendingCleanupPhase] (by name), keeping everything else. */
    @Query("UPDATE pending_cleanups SET phase = :phase WHERE operation_id = :operationId")
    suspend fun updatePhase(operationId: String, phase: String)

    /**
     * Audio files a delete or replace has removed from the library but not yet from disk. Scans
     * skip these paths, so a file still waiting to be deleted is never imported again.
     */
    @Query("SELECT file_path FROM pending_cleanups WHERE operation_type = 'DELETE' AND file_path IS NOT NULL")
    suspend fun getPendingDeletePaths(): List<String>

    /** Clears every delete record for [filePath], once that file is confirmed gone. */
    @Query("DELETE FROM pending_cleanups WHERE operation_type = 'DELETE' AND file_path = :filePath")
    suspend fun clearDeletesForPath(filePath: String)
}
