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
}
