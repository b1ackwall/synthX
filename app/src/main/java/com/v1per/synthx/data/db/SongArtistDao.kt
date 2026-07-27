package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SongArtistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongArtist(crossRef: SongArtistEntity)

    @Query("DELETE FROM song_artists WHERE song_id = :songId")
    suspend fun deleteSongArtists(songId: String)
}
