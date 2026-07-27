package com.v1per.synthx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_groups")
data class PlaylistGroupEntity(
    // UUID
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
