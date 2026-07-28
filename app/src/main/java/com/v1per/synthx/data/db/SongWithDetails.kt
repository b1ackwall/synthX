package com.v1per.synthx.data.db

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

// Room loads the artists relation in one batched query, but Junction does not
// preserve song_artists.position — callers must re-sort via getSongArtistRefs.
data class SongWithDetails(
    @Embedded val song: SongEntity,
    @Relation(
        parentColumn = "album_id",
        entityColumn = "id",
        entity = AlbumEntity::class,
    )
    val album: AlbumEntity?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SongArtistEntity::class,
            parentColumn = "song_id",
            entityColumn = "artist_id",
        ),
        entity = ArtistEntity::class,
    )
    val artists: List<ArtistEntity>,
)
