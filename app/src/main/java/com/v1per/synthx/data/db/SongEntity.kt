package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("album_id"),
        Index(value = ["file_path"], unique = true),
    ],
)
data class SongEntity(
    // SHA-256 of the first 64KB + last 64KB + file size, lowercase hex
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    val duration: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String?,
    @ColumnInfo(name = "album_art_full_uri", defaultValue = "NULL") val albumArtFullUri: String? = null,
    val year: Int?,
    @ColumnInfo(name = "track_number") val trackNumber: Int? = null,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    @ColumnInfo(name = "is_missing", defaultValue = "0") val isMissing: Boolean = false,
    @ColumnInfo(name = "missing_since") val missingSince: Long? = null,
    /**
     * Starred = "not yet organized into a playlist". True on insert for every newly scanned
     * song, and cleared only by an explicit user dismissal (SongDao.dismissFromStarred) —
     * never automatically by joining a playlist, so a song the user deliberately kept starred
     * stays starred.
     *
     * The column is named `in_inbox` for historical reasons (the feature was scoped as
     * "Inbox" before the rename to "Starred"); renaming it would cost a full table rebuild
     * for zero behavioural gain, so the Kotlin/UI vocabulary carries the current name and
     * the column keeps the old one. [defaultValue] must match MIGRATION_7_8's SQL default
     * exactly or Room's schema validation fails at open time — same contract as [isMissing].
     */
    @ColumnInfo(name = "in_inbox", defaultValue = "1") val isStarred: Boolean = true,
)
