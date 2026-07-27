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
)
