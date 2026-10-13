package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlists",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("group_id")],
)
data class PlaylistEntity(
    // UUID
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "group_id") val groupId: String?,
    val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "cover_art_uri") val coverArtUri: String?,
    /**
     * Written together at creation (or by PlaylistArtBackfill for rows that predate this
     * schema), and immutable thereafter: the two values that fully determine this playlist's
     * generated structural cover art. [artHue] is an HSL hue in degrees — one of the 90
     * perceptually spaced hues in PlaylistArtHues for playlists created now, or a multiple of 4
     * (the previous palette) for older rows — chosen to sit as far as possible, by eye, from
     * every hue already in use; [artStructureSeed] seeds the
     * symmetry plane, path walk and mirroring. Both NULL means "not generated yet", which
     * renders as the flat ArtPlaceholder.
     *
     * [ColumnInfo.defaultValue] must match MIGRATION_13_14's SQL default exactly or Room's
     * open-time schema validation throws.
     */
    @ColumnInfo(name = "art_hue", defaultValue = "NULL") val artHue: Int? = null,
    @ColumnInfo(name = "art_structure_seed", defaultValue = "NULL") val artStructureSeed: Long? = null,
)
