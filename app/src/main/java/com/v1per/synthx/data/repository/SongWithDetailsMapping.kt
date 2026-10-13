package com.v1per.synthx.data.repository

import com.v1per.synthx.data.db.SongArtistEntity
import com.v1per.synthx.data.db.SongWithDetails

/**
 * The single `SongWithDetails` -> [Song] mapping for the whole module.
 *
 * Was a private member of [SongRepositoryImpl] until [com.v1per.synthx.data.scanner.graduateSong]
 * also needed a fully-populated domain [Song] to hand to
 * [com.v1per.synthx.data.SongRemapEvents]. Lifted to an internal top-level extension rather than
 * copied: a second, drifting copy of this mapping would silently hand the playback engine a
 * degraded [Song] (missing artists/album) for the graduated slot, which is exactly the
 * kind of staleness the remap event exists to prevent. Same package, same name, same signature,
 * so every existing call site resolves to it unchanged.
 */
internal fun SongWithDetails.toDomain(refs: List<SongArtistEntity>): Song {
    // @Relation via Junction returns artists unordered — restore song_artists.position
    val positionByArtistId = refs.associate { it.artistId to it.position }
    val orderedArtists = artists.sortedBy { positionByArtistId[it.id] ?: Int.MAX_VALUE }
    val artist = if (orderedArtists.isEmpty()) {
        "Unknown Artist"
    } else {
        orderedArtists.joinToString(", ") { it.name }
    }
    return Song(
        id = song.id,
        title = song.title,
        artist = artist,
        album = album?.title ?: "Unknown Album",
        duration = song.duration,
        filePath = song.filePath,
        albumArtUri = song.albumArtUri,
        albumArtFullUri = song.albumArtFullUri,
        isMissing = song.isMissing,
        dateAdded = song.dateAdded,
        albumId = song.albumId,
        // Same ordered list the display string is built from — no extra query.
        artists = orderedArtists.map { Artist(id = it.id, name = it.name) },
    )
}
