package com.v1per.synthx.data.repository

import androidx.room.ColumnInfo

/**
 * Projection for the Issues list. `missingSince` is non-null because the query that
 * produces it is scoped to `is_missing = 1`, and the flag is only ever set together
 * with the timestamp (see SongDao.markMissing).
 */
data class MissingSongInfo(
    val id: String,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "missing_since") val missingSince: Long,
)
