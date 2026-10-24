package com.v1per.synthx.data.backup

import kotlinx.serialization.Serializable

/**
 * Human-readable, whole-app-independent snapshot of playlist structure — a disaster-recovery
 * aid separate from [BackupManager]'s whole-DB snapshots. A script could rebuild every
 * playlist from this file alone, matching songs by their key in [songs] (the same
 * SHA-256 [com.v1per.synthx.data.db.SongEntity.id]) without ever opening `synthx.db`.
 *
 * [songs] is keyed by song hash rather than embedded per playlist entry, so the file's size
 * tracks the number of *distinct* songs referenced across all playlists, not the (larger)
 * total count of playlist-song rows.
 */
@Serializable
data class PlaylistExport(
    val exportedAt: Long,
    /**
     * Names the song fingerprinting algorithm ([ExportedSong] hashes are the sampled SHA-256 from
     * HashUtils: first 64KB + last 64KB + file size) as of this export. A future change to the
     * fingerprinting algorithm changes this value, so a disaster-recovery script can detect a
     * mismatch instead of silently matching wrong songs.
     */
    val hashStrategy: String,
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
    /**
     * Index in this export's group ordering (0-based).
     * [com.v1per.synthx.data.db.PlaylistGroupEntity.position] is not meaningfully maintained
     * today (always inserted as 0), so this captures the order groups
     * were actually returned in at export time rather than that dead column.
     */
    val position: Int,
    val playlists: List<ExportedPlaylist>,
)

@Serializable
data class ExportedPlaylist(
    val name: String,
    val description: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * Index of this playlist within its group (or within the ungrouped list) at export time
     * (0-based). [com.v1per.synthx.data.db.PlaylistEntity.position] is not meaningfully
     * maintained today (always inserted as 0), so this captures actual query-return order
     * rather than that dead column.
     */
    val position: Int,
    /** Song hashes, in playlist order — look each one up in [PlaylistExport.songs]. */
    val songHashes: List<String>,
)
