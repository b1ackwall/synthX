package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Which destructive operation a [PendingCleanupEntity] is tracking. */
enum class PendingCleanupType { DELETE, GRADUATE }

/**
 * Where a [PendingCleanupEntity] sits in its operation's crash-safe protocol.
 *
 * [Committed] (delete/replace): the DB row is already gone; [PendingCleanupEntity.filePath]'s
 * audio file (and the song's cached art) may or may not have been cleaned up yet.
 *
 * [SourceDeleted] (graduate): the source audio file is already gone from disk, and the DB row
 * still points at it; [PendingCleanupEntity.publishedPath] is where it needs to be re-pointed to.
 */
enum class PendingCleanupPhase { Committed, SourceDeleted }

/**
 * A durable record of a destructive operation between its DB commit and physical completion —
 * see P3-T1 (R2-F09 / R4-F-D4 / R4-F-D5). Written inside the SAME transaction that makes the
 * operation's irreversible DB change, so it is durable before that change is observable, and
 * cleared only once the operation's remaining physical work has actually finished.
 *
 * Deliberately carries no foreign key to `songs`: for [PendingCleanupPhase.Committed] the row
 * this describes is already gone by the time the record exists, and for
 * [PendingCleanupPhase.SourceDeleted] the row still exists but is mid-transition — a CASCADE
 * here would either be impossible (row gone) or delete the record out from under the very
 * operation it is meant to protect.
 */
@Entity(tableName = "pending_cleanups")
data class PendingCleanupEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    /** [PendingCleanupType] name. */
    @ColumnInfo(name = "operation_type") val operationType: String,
    /** Delete/replace: the audio file to remove. Graduate: unused (null). */
    @ColumnInfo(name = "file_path") val filePath: String?,
    /** Graduate: the path the DB row must be re-pointed to. Delete/replace: unused (null). */
    @ColumnInfo(name = "published_path") val publishedPath: String?,
    /** [PendingCleanupPhase] name. */
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
