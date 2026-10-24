package com.v1per.synthx.data.repository

import android.net.Uri
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.ArtRecoveryResult
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

    /** Re-extracts art for [songIds], or the whole library when null. */
    suspend fun recoverMissingArt(songIds: List<String>? = null): ArtRecoveryResult

    /**
     * Permanently deletes a song: its row, its art files, its **audio file on disk**, and (via
     * CASCADE) every row referencing it. Also notifies live playback so a queued or playing
     * occurrence is removed rather than left pointing at a file that no longer exists — see
     * [com.v1per.synthx.data.SongDeletionEvents].
     *
     * @return the post-commit cleanup state. [SongDeletionOutcome.FileRemained] confirms that the
     *   audio remains; [SongDeletionOutcome.CleanupUncertain] means cleanup could not be verified.
     */
    suspend fun deleteSong(id: String): SongDeletionOutcome

    /** Resolves named playlist consequences immediately before delete confirmation. */
    suspend fun previewDeleteSong(id: String): DeleteSongPreview

    /** Retries only the physical audio cleanup after the library row has already been removed. */
    suspend fun retryAudioFileDeletion(filePath: String): SongDeletionOutcome

    /**
     * Same effect as [deleteSong], but the call itself is launched on this repository's own
     * process-lifetime scope rather than the caller's — it keeps running to
     * completion even if the caller's own coroutine scope is cancelled first, e.g. an
     * `IssuesViewModel` whose `NavBackStackEntry` is popped mid-delete. [onResult] is invoked
     * exactly once, off the caller's scope, once the delete finishes or throws — implementations
     * must not swallow the callback on a non-cancellation failure.
     */
    fun deleteSongDetached(id: String, onResult: (Result<SongDeletionOutcome>) -> Unit)

    /**
     * Read-only dry run of [replaceSong], for the review step's "here is what will happen"
     * screen. Never writes.
     *
     * The result distinguishes which selected row disappeared and carries the exact membership
     * snapshot that [replaceSong] must validate before writing.
     */
    suspend fun previewReplaceSong(oldSongId: String, newSongId: String): ReplaceSongPreviewResult

    /**
     * Transplants the old song's library identity onto the new song, then completely deletes the
     * old song (row + audio file, exactly as [deleteSong] does).
     *
     * "Identity" is: every playlist membership the old song held, at the same position (see
     * [PlaylistConflictResolution] for what happens where both songs are already members), and
     * its `date_added`. Starred status is *not* copied — the new song's own value is kept, except
     * that a new song which ends up in at least one playlist is un-starred, since starred means
     * "not yet organized into a playlist".
     *
     * [expectedMemberships] must be the snapshot returned by [previewReplaceSong]. If either
     * song's membership rows changed, the operation returns [ReplaceSongResult.PreviewStale]
     * before writing anything.
     */
    suspend fun replaceSong(
        oldSongId: String,
        newSongId: String,
        conflictResolution: PlaylistConflictResolution,
        expectedMemberships: ReplaceSongMembershipSnapshot,
    ): ReplaceSongResult

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
    fun graduateSong(
        songId: String,
        rootMusicFolderUri: Uri,
        rootNewArrivalsFolderUri: Uri? = null,
    ): Flow<GraduationStage>

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

    /** Whether a playlist row with this id exists right now; a one-shot read, not a live query. */
    suspend fun playlistExists(playlistId: String): Boolean

    /** Ids of every playlist [songId] currently belongs to — drives the "already in this
     *  playlist" indicator in the Add-to-Playlist picker. */
    suspend fun getPlaylistIdsForSong(songId: String): List<String>

    /**
     * Appends to the end of the playlist.
     * @return false when the song was already a member, in which case nothing changed.
     */
    suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    /** Resolves arbitrary song ids to [Song]s — used to rehydrate a live player timeline back
     *  into domain objects when no in-memory cache already has them (e.g. reconnecting to a
     *  still-running playback service). Duplicate ids collapse and surviving songs retain the
     *  caller's first-occurrence order. */
    suspend fun getSongsByIds(ids: List<String>): List<Song>

    // Playback queue — persisted so it survives a restart

    /** The saved queue, retaining each occurrence's slot identity. */
    suspend fun getQueueSlotsForTest(): List<QueueSlot>

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

    /**
     * [getQueueSnapshot] plus the baseline order, read together as one consistent snapshot
     * — see [com.v1per.synthx.data.db.QueueDao.getFullQueueSnapshot]. Closes the
     * split-read window a separate [getBaselineQueueSlots] call would leave against an
     * atomically-written pair.
     */
    suspend fun getQueueSnapshotWithBaseline(): Pair<QueueSnapshot, List<QueueSlot>>
}

/**
 * Result of a requested complete delete. The row is gone for [Deleted], [FileRemained], and
 * [CleanupUncertain]. [Blocked] is returned only when the live preflight establishes a definite
 * safety problem; in that case the row and files are untouched.
 *
 * [Deleted] means the audio was deleted or confirmed already absent and every other cleanup step
 * completed. [FileRemained] means the provider confirmed the audio existed but refused or failed
 * its deletion. [CleanupUncertain] means the audio's state could not be established, or another
 * post-commit side effect failed; callers must not describe that state as a complete success or as
 * a rolled-back operation.
 */
sealed interface SongDeletionOutcome {
    data object Deleted : SongDeletionOutcome
    data object FileRemained : SongDeletionOutcome
    data object CleanupUncertain : SongDeletionOutcome
    data class Blocked(val reason: DestructiveFilePreflightResult.Blocking) : SongDeletionOutcome
}

/** The saved resume position: an exact slot id plus position and playback modes. */
data class QueueCursor(
    val currentSlotId: String?,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
)

/** A consistent persisted queue/cursor read, including rows that no longer resolve to songs. */
data class QueueSnapshot(val slots: List<QueueSlot>, val storedSlotCount: Int, val cursor: QueueCursor?)

/** Internal queue-only contract: the caller already owns a RestoreCoordinator operation permit. */
internal interface AdmittedQueuePersistenceRepository {
    /** Replaces the queue structure and the resume cursor in one atomic commit — see
     *  [com.v1per.synthx.data.db.QueueDao.replaceQueueAndCursor]. */
    suspend fun setQueueState(slots: List<QueueSlotRecord>, currentSlotId: String?, positionMs: Long)

    /** Cursor-only counterpart of [setQueueState] — see
     *  [com.v1per.synthx.data.db.QueueDao.updateCursor]. Does not touch the persisted queue. */
    suspend fun setCursorAlreadyAdmitted(currentSlotId: String?, positionMs: Long)
    suspend fun setBaselineQueueSlots(slots: List<QueueSlotRecord>)

    /** Narrow shuffle-mode write — see [com.v1per.synthx.data.db.QueueDao.updateShuffleEnabled]. */
    suspend fun setShuffleEnabledAlreadyAdmitted(enabled: Boolean)

    /** Narrow repeat-mode write — see [com.v1per.synthx.data.db.QueueDao.updateRepeatMode]. */
    suspend fun setRepeatModeAlreadyAdmitted(mode: Int)

    /**
     * The atomic write for a call site that changes more than one of {baseline order, active
     * order, shuffle flag, repeat mode, cursor} at once — see
     * [com.v1per.synthx.data.db.QueueDao.replaceQueueBaselineAndCursor].
     */
    suspend fun setUnifiedQueueState(
        activeSlots: List<QueueSlotRecord>,
        baselineSlots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
    )
}
