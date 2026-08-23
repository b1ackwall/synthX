package com.v1per.synthx.data.backup

import kotlinx.serialization.Serializable

/**
 * Human-readable, whole-app-independent snapshot of playlist structure — a disaster-recovery
 * aid separate from [BackupManager]'s whole-DB snapshots. A script could rebuild every
 * playlist from this file alone, matching songs by [ExportedSong.songHash] (the same
 * SHA-256 [com.v1per.synthx.data.db.SongEntity.id]) without ever opening `synthx.db`.
 *
 * [songs] is keyed by song hash rather than embedded per playlist entry, so the file's size
 * tracks the number of *distinct* songs referenced across all playlists, not the (larger)
 * total count of playlist-song rows.
 */
@Serializable
data class PlaylistExport(
    val exportedAt: Long,
    val songs: Map<String, ExportedSong>,
    val groups: List<ExportedGroup>,
    val ungroupedPlaylists: List<ExportedPlaylist>,
)

@Serializable
data class ExportedSong(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val filePath: String,
    val isMissing: Boolean,
)

@Serializable
data class ExportedGroup(
    val name: String,
    val playlists: List<ExportedPlaylist>,
)

@Serializable
data class ExportedPlaylist(
    val name: String,
    /** Song hashes, in playlist order — look each one up in [PlaylistExport.songs]. */
    val songHashes: List<String>,
)
