package com.v1per.synthx.data.repository

import android.net.Uri
import com.v1per.synthx.data.scanner.ArtRecoveryResult
import com.v1per.synthx.data.scanner.PaletteRecoveryResult
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.data.scanner.RootOrganizationCheckResult
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    fun getSongs(): Flow<List<Song>>
    fun getArtists(): Flow<List<Artist>>
    fun getSongsForArtist(artistId: String): Flow<List<Song>>
    fun getAlbums(): Flow<List<Album>>
    fun getSongsForAlbum(albumId: String): Flow<List<Song>>

    // Issues — songs flagged is_missing
    fun getMissingSongs(): Flow<List<MissingSongInfo>>
    fun getMissingSongCount(): Flow<Int>

    // Issues — songs whose art file(s) are gone from disk despite the row pointing at them
    // (e.g. a restore, since album art is deliberately excluded from backups). See ArtRecovery.

    /** Ids of songs currently missing their art file on disk. Read-only — see [recoverMissingArt]. */
    suspend fun findMissingArtSongIds(): List<String>

    /** Re-extracts art for [songIds], or the whole library when null. */
    suspend fun recoverMissingArt(songIds: List<String>? = null): ArtRecoveryResult

    /** Extracts missing background swatches from the affected songs' existing artwork. */
    suspend fun recoverPaletteData(songIds: List<String>): PaletteRecoveryResult

    /** Permanently deletes a song, its art file, and (via CASCADE) all rows referencing it. */
    suspend fun deleteSong(id: String)

    // Starred — songs not yet organized into a playlist

    /** Starred, non-missing songs, newest first. */
    fun getStarredSongs(): Flow<List<Song>>

    /** Non-missing songs, newest-added first, capped at [limit]. */
    fun getRecentSongs(limit: Int): Flow<List<Song>>

    /** How many songs [getStarredSongs] would return — for the Library row's badge. */
    fun getStarredSongCount(): Flow<Int>

    /**
     * Drops the song off the Starred list. One-way and non-destructive: it clears only the
     * flag, never playlist membership or the song itself, and nothing sets the flag back.
     */
    suspend fun dismissFromStarred(songId: String)

    /** Whether the song belongs to at least one playlist — gates the dismissal above. */
    suspend fun isInAnyPlaylist(songId: String): Boolean

    /**
     * Physically graduates a song out of wherever it currently lives (typically New Arrivals)
     * into [rootMusicFolderUri] — copy, hash-verify, delete original — then updates its DB
     * row's file_path and clears isStarred. See
     * [com.v1per.synthx.data.scanner.graduateSong] for the stage-by-stage mechanics.
     *
     * Emits every [GraduationStage] as the operation progresses; the last value emitted is
     * always the terminal stage ([GraduationStage.Done] or [GraduationStage.Failed]).
     */
    fun graduateSong(songId: String, rootMusicFolderUri: Uri): Flow<GraduationStage>

    /**
     * Debug tooling: read-only audit of the root music folder — walks the live filesystem
     * and classifies every audio file found there against the DB (unorganized vs. unindexed),
     * without writing anything. See
     * [com.v1per.synthx.data.scanner.checkRootLibraryOrganization] for the classification
     * rules.
     *
     * @return `null` if [musicFolderUri]'s root can't currently be resolved (not configured,
     *   revoked grant, ejected volume, etc.) rather than a false "fully organized" result.
     */
    suspend fun checkRootLibraryOrganization(musicFolderUri: Uri): RootOrganizationCheckResult?

    // Groups
    fun getAllGroups(): Flow<List<PlaylistGroup>>
    suspend fun createGroup(name: String)
    suspend fun renameGroup(id: String, name: String)
    suspend fun deleteGroup(id: String)

    // Playlists
    fun getAllPlaylists(): Flow<List<Playlist>>
    fun getUngroupedPlaylists(): Flow<List<Playlist>>
    fun getPlaylistsForGroup(groupId: String): Flow<List<Playlist>>
    /** @return the id of the newly created playlist. */
    suspend fun createPlaylist(name: String, groupId: String?): String
    suspend fun renamePlaylist(id: String, name: String)
    suspend fun movePlaylistToGroup(id: String, groupId: String?)
    suspend fun deletePlaylist(id: String)

    // Playlist songs
    fun getSongsForPlaylist(playlistId: String): Flow<List<Song>>

    /**
     * Appends to the end of the playlist.
     * @return false when the song was already a member, in which case nothing changed.
     */
    suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    /** Resolves arbitrary song ids to [Song]s — used to rehydrate a live player timeline back
     *  into domain objects when no in-memory cache already has them (e.g. reconnecting to a
     *  still-running playback service). Order is not preserved; callers key off [Song.id]. */
    suspend fun getSongsByIds(ids: List<String>): List<Song>

    // Playback queue — persisted so it survives a restart

    /** The saved queue, retaining each occurrence's slot identity. */
    suspend fun getQueueSlots(): List<QueueSlot>

    /**
     * The saved queue rows and resume cursor, read as one consistent snapshot (see
     * [QueueCursor]) — the read-side counterpart of [AdmittedQueuePersistenceRepository]'s
     * atomic write.
     */
    suspend fun getQueueSnapshot(): QueueSnapshot

    // Baseline order — the app-managed source-of-truth sequence, persisted separately
    // from the live/active queue above so it can survive shuffle toggles.

    /** The saved baseline, retaining each occurrence's slot identity. */
    suspend fun getBaselineQueueSlots(): List<QueueSlot>
}

/** The saved resume position: an exact slot id plus position and playback modes. */
data class QueueCursor(
    val currentSlotId: String?,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
)

/** A consistent persisted queue/cursor read, including rows that no longer resolve to songs. */
data class QueueSnapshot(
    val slots: List<QueueSlot>,
    val storedSlotCount: Int,
    val cursor: QueueCursor?,
)

/** Internal queue-only contract: the caller already owns a RestoreCoordinator operation permit. */
internal interface AdmittedQueuePersistenceRepository {
    /** Replaces the queue structure and the resume cursor in one atomic commit — see
     *  [com.v1per.synthx.data.db.QueueDao.replaceQueueAndCursor]. */
    suspend fun setQueueState(
        slots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
    )

    /** Cursor-only counterpart of [setQueueState] — see
     *  [com.v1per.synthx.data.db.QueueDao.updateCursor]. Does not touch the persisted queue. */
    suspend fun setCursorAlreadyAdmitted(currentSlotId: String?, positionMs: Long)
    suspend fun setBaselineQueueSlots(slots: List<QueueSlotRecord>)

    /** Narrow shuffle-mode write — see [com.v1per.synthx.data.db.QueueDao.updateShuffleEnabled]. */
    suspend fun setShuffleEnabledAlreadyAdmitted(enabled: Boolean)

    /** Narrow repeat-mode write — see [com.v1per.synthx.data.db.QueueDao.updateRepeatMode]. */
    suspend fun setRepeatModeAlreadyAdmitted(mode: Int)
}
