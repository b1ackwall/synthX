package com.v1per.synthx.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {

    @Upsert
    suspend fun upsertArtist(artist: ArtistEntity)

    @Query("SELECT * FROM artists WHERE name_normalized = :normalized LIMIT 1")
    suspend fun getArtistByNormalizedName(normalized: String): ArtistEntity?

    /**
     * Look up an artist by its normalized name, inserting it if absent. Atomic so
     * two concurrent scans cannot both miss the lookup and mint duplicate rows —
     * `name_normalized` is indexed but not unique, so SQLite would accept both.
     */
    @Transaction
    suspend fun getOrInsertArtist(name: String, normalized: String): ArtistEntity {
        getArtistByNormalizedName(normalized)?.let { return it }
        val artist = ArtistEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            nameNormalized = normalized,
        )
        upsertArtist(artist)
        return artist
    }

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun getAllArtists(): Flow<List<ArtistEntity>>

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT DISTINCT artist_id FROM song_artists)")
    suspend fun deleteOrphanArtists()
}
