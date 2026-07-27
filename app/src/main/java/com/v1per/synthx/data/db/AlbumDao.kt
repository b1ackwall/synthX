package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AlbumDao {

    @Upsert
    suspend fun upsertAlbum(album: AlbumEntity)

    @Query("SELECT * FROM albums WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun getAlbumByTitle(title: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getAlbumById(id: String): AlbumEntity?

    // TODO v2.x: getAllAlbums for the Library albums tab, orphan cleanup
}
