package com.v1per.synthx.data.repository

import android.net.Uri
import com.v1per.synthx.data.scanner.ScanResult
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    fun getSongs(): Flow<List<Song>>
    fun getArtists(): Flow<List<Artist>>
    fun getSongsForArtist(artistId: String): Flow<List<Song>>
    fun getAlbums(): Flow<List<Album>>
    fun getSongsForAlbum(albumId: String): Flow<List<Song>>
    /**
     * @param reconcileMissing `false` skips the library-wide missing-detection pass — see
     *   [com.v1per.synthx.data.scanner.FolderScanner.scan]. Only a caller that can account
     *   for the whole library may reconcile; a secondary-tree scan must not.
     * @param otherRootUris the configured audio roots *other than* [folderUri], enumerated
     *   so their files count as found. Required whenever [reconcileMissing] is `true` and
     *   more than one root is configured, or songs under the others are flagged missing.
     * @param isNewArrivalsRoot whether [folderUri] is the New Arrivals tree rather than the
     *   main library root — see [com.v1per.synthx.data.scanner.FolderScanner.scan].
     */
    suspend fun scanLibrary(
        folderUri: Uri,
        reconcileMissing: Boolean = true,
        otherRootUris: List<Uri> = emptyList(),
        isNewArrivalsRoot: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult

    /**
     * Lightweight existence check over every stored song. Returns the count newly flagged
     * missing.
     * @param configuredRootUris every currently configured audio root (primary + others).
     *   A song under a root that fails its reachability check is left untouched this pass
     *   rather than risking a false missing-flag — see
     *   [com.v1per.synthx.data.scanner.FolderScanner.verify].
     */
    suspend fun verifyLibrary(configuredRootUris: List<Uri>): Int

    // Issues — songs flagged is_missing
    fun getMissingSongs(): Flow<List<MissingSongInfo>>
    fun getMissingSongCount(): Flow<Int>

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
     * Temporary debug tooling — safe to delete independently of PlaylistSeeder. Bulk version
     * of [dismissFromStarred] for restoring test state after a fresh install/empty DB, not a
     * production feature. See [com.v1per.synthx.data.db.SongDao.removeOrganizedFromStarred]
     * for the full removal checklist.
     *
     * @return the number of songs actually cleared.
     */
    suspend fun removeOrganizedFromStarred(): Int

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

    /** The saved queue, resolved to songs. Shorter than [getQueueSongIds] when songs went away. */
    suspend fun getQueueSongs(): List<Song>

    /** The saved queue as raw ids, including entries that no longer resolve to a playable song. */
    suspend fun getQueueSongIds(): List<String>

    /**
     * The saved queue ids and resume cursor, read as one consistent snapshot (see
     * [QueueCursor]) — the read-side counterpart of [AdmittedQueuePersistenceRepository]'s
     * atomic write.
     */
    suspend fun getQueueSongIdsAndCursor(): Pair<List<String>, QueueCursor?>

    /** Overwrites the saved queue with [songIds], in order. */
    suspend fun setQueueSongs(songIds: List<String>)

    // Baseline order — the app-managed source-of-truth sequence, persisted separately
    // from the live/active queue above so it can survive shuffle toggles.

    /** The saved baseline, resolved to songs. Shorter than [getBaselineQueueSongIds] when songs went away. */
    suspend fun getBaselineQueueSongs(): List<Song>

    /** The saved baseline as raw ids, including entries that no longer resolve to a playable song. */
    suspend fun getBaselineQueueSongIds(): List<String>

    /** Overwrites the saved baseline with [songIds], in order. */
    suspend fun setBaselineQueueSongs(songIds: List<String>)
}

/** The saved resume position: a raw index into the queue as persisted alongside it, and the
 *  playback position within that item, plus the shuffle/repeat mode the queue was in. See
 *  PlaybackCursorEntity's kdoc for why none of these are written independently of the queue
 *  structure any more. */
data class QueueCursor(val index: Int, val positionMs: Long, val shuffleEnabled: Boolean, val repeatMode: Int)

/** Internal queue-only contract: the caller already owns a RestoreCoordinator operation permit. */
internal interface AdmittedQueuePersistenceRepository {
    /** Replaces the queue structure and the resume cursor in one atomic commit — see
     *  [com.v1per.synthx.data.db.QueueDao.replaceQueueAndCursor]. */
    suspend fun setQueueStateAlreadyAdmitted(songIds: List<String>, queueIndex: Int, positionMs: Long)

    /** Cursor-only counterpart of [setQueueStateAlreadyAdmitted] — see
     *  [com.v1per.synthx.data.db.QueueDao.updateCursor]. Does not touch the persisted queue. */
    suspend fun setCursorAlreadyAdmitted(queueIndex: Int, positionMs: Long)
    suspend fun setBaselineQueueSongsAlreadyAdmitted(songIds: List<String>)

    /** Narrow shuffle-mode write — see [com.v1per.synthx.data.db.QueueDao.updateShuffleEnabled]. */
    suspend fun setShuffleEnabledAlreadyAdmitted(enabled: Boolean)

    /** Narrow repeat-mode write — see [com.v1per.synthx.data.db.QueueDao.updateRepeatMode]. */
    suspend fun setRepeatModeAlreadyAdmitted(mode: Int)
}
