package com.v1per.synthx.data.repository

import android.content.Context
import android.net.Uri
import com.v1per.synthx.BuildConfig
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.QueueDao
import com.v1per.synthx.data.db.SongArtistEntity
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongWithDetails
import com.v1per.synthx.data.db.SynthXDatabase
import android.util.Log
import androidx.room.withTransaction
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongAudioFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import com.v1per.synthx.data.playlistart.selectDistancedHue
import com.v1per.synthx.data.scanner.ArtRecovery
import com.v1per.synthx.data.scanner.ArtRecoveryResult
import com.v1per.synthx.data.scanner.PaletteRecovery
import com.v1per.synthx.data.scanner.PaletteRecoveryResult
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.data.scanner.RootOrganizationCheckResult
import com.v1per.synthx.data.scanner.ScanResult
import com.v1per.synthx.data.scanner.walkAndClassifyRootOrganization
import com.v1per.synthx.data.scanner.graduateSong as graduateSongFile
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.preferences.DestructiveActionTracker
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val TAG = "SongRepositoryImpl"

class SongRepositoryImpl(
    private val context: Context,
    private val database: SynthXDatabase,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val playlistDao: PlaylistDao,
    private val queueDao: QueueDao,
    private val folderScanner: FolderScanner,
    private val backupManager: BackupManager,
    private val playlistExportManager: PlaylistExportManager,
) : SongRepository, AdmittedQueuePersistenceRepository {

    private suspend fun <T> dbOperation(block: suspend () -> T): T =
        RepositoryDbOperation.run(block)

    // This class is a singleton (see companion.create), so this scope effectively lives as
    // long as the app process does — same shape as ExoPlayerEngine.engineScope /
    // QueuePersistence.scope at their own instance-lifetime level.
    private val repositoryJob = SupervisorJob()
    private val repositoryScope = CoroutineScope(repositoryJob + Dispatchers.Default)

    // Shared across every getSongs() subscriber instead of re-querying and re-mapping the
    // full library (~1500 songs) on every SongsViewModel instance. Lazily rather than
    // WhileSubscribed: local song data changes rarely and Songs is a frequently-revisited
    // screen, so once the query has run once it's worth keeping warm for the rest of the
    // process — instant navigation matters more here than releasing this one cache early.
    // Room's Flow still auto-invalidates on real table changes, so this stays correct
    // across scans/edits regardless of how long the cache is held.
    // general 7.3: nothing upstream of this .stateIn can ever catch a failure here — once the
    // upstream Flow (including the extra getAllSongArtistRefs() query below) throws, the
    // stateIn producer coroutine dies and no downstream .catch{} on this StateFlow's consumers
    // can ever see it. The .catch below is the only point that can intercept it: it logs, then
    // degrades to the last successfully computed song list rather than crashing the process or
    // freezing on an empty list. The producer coroutine still ends after that — Room's live
    // invalidation is not resumed, so this is a one-time degrade-to-last-known-good, not a
    // recoverable retry; a fresh app process is what actually restores live updates.
    private var lastKnownSongs: List<Song> = emptyList()
    private val songsFlow: StateFlow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getAllSongsWithDetails() }.map { entries ->
            // One refs query per emission — 2 queries total regardless of library size
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
            entries.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
                .also { lastKnownSongs = it }
        }.catch { e ->
            Log.e(TAG, "songsFlow upstream failure — degrading to last known songs", e)
            emit(lastKnownSongs)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getSongs(): Flow<List<Song>> = songsFlow

    /** Refresh only the playing song's stored palette; no artwork I/O or artist joins. */
    internal fun observePaletteSwatches(songId: String): Flow<List<SongPaletteSwatch>?> =
        RepositoryDbFlow.observe { songDao.observeSongById(songId) }
            .map { it?.toPaletteSwatches() }
            .distinctUntilChanged()

    // Same caching rationale as songsFlow above — shared across every getArtists()
    // subscriber (Artists list, Search) instead of re-querying per collector.
    // See songsFlow above for why the .catch must sit here, upstream of .stateIn.
    private var lastKnownArtists: List<Artist> = emptyList()
    private val artistsFlow: StateFlow<List<Artist>> =
        RepositoryDbFlow.observe { artistDao.getAllArtists() }.map { entities ->
            entities.map {
                Artist(id = it.id, name = it.name, allSongsMissing = it.allSongsMissing)
            }.also { lastKnownArtists = it }
        }.catch { e ->
            Log.e(TAG, "artistsFlow upstream failure — degrading to last known artists", e)
            emit(lastKnownArtists)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getArtists(): Flow<List<Artist>> = artistsFlow

    // Sort order (title) stays in the DAO query; the Flow conversion preserves it.
    override fun getSongsForArtist(artistId: String): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getSongsWithDetailsForArtist(artistId) }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    // Same caching rationale as songsFlow above — shared across every getAlbums()
    // subscriber (Albums list, Search) instead of re-querying per collector.
    // See songsFlow above for why the .catch must sit here, upstream of .stateIn.
    private var lastKnownAlbums: List<Album> = emptyList()
    private val albumsFlow: StateFlow<List<Album>> =
        RepositoryDbFlow.observe { albumDao.getAllAlbums() }.map { entries ->
            entries.map {
                Album(
                    id = it.id,
                    title = it.title,
                    artist = it.artistName,
                    albumArtUri = it.albumArtUri,
                    allSongsMissing = it.allSongsMissing,
                )
            }.also { lastKnownAlbums = it }
        }.catch { e ->
            Log.e(TAG, "albumsFlow upstream failure — degrading to last known albums", e)
            emit(lastKnownAlbums)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getAlbums(): Flow<List<Album>> = albumsFlow

    // Sort order (track number, then title) stays in the DAO query.
    override fun getSongsForAlbum(albumId: String): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getSongsWithDetailsForAlbum(albumId) }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    internal suspend fun scanLibrary(
        folderUri: Uri,
        reconcileMissing: Boolean = true,
        otherRootUris: List<Uri> = emptyList(),
        isNewArrivalsRoot: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = folderScanner.scanAlreadyAdmitted(
        folderUri,
        reconcileMissing,
        otherRootUris,
        isNewArrivalsRoot,
        onProgress,
    )

    internal suspend fun verifyLibrary(configuredRootUris: List<Uri>): Int =
        folderScanner.verifyAlreadyAdmitted(configuredRootUris)

    /** Total library size, for gauging a scan's impact as a percentage. */
    internal suspend fun getSongCountAlreadyAdmitted(): Int = songDao.getSongCount()

    /**
     * Permanently removes a song. Its song_artists and playlist_songs rows go with it via
     * FK CASCADE — nothing here deletes them explicitly.
     *
     * The album-art re-point + row delete (via [deleteSongRowWithArtRepoint]) share one
     * transaction; the art file, the audio file, and the live-playback notice all follow only
     * after it commits, since none of the three can be rolled back alongside the DB. Same shared
     * cleanup FolderScanner's internal evictions use, so both deletion paths stay in sync.
     *
     * The `file_path` is read inside the same transaction that deletes the row — afterwards there
     * is nothing left to read it from.
     */
    override suspend fun deleteSong(id: String): SongDeletionOutcome = withContext(Dispatchers.IO) {
        // Backup-then-act: a failed backup (no folder configured) must not block the
        // delete the user explicitly asked for.
        // Backup waits for finite operations to drain, so it must precede our own permit.
        backupManager.createBackup(BackupTier.Auto, BackupReason.DELETE_SONG)

        // Recheck admission: restore may have begun while the backup was running.
        dbOperation {
            val filePath = database.withTransaction {
                val existing = songDao.getSongById(id)
                deleteSongRowWithArtRepoint(songDao, albumDao, id)
                existing?.filePath
            }
            finishSongDeletion(id, filePath)
        }
    }

    /**
     * The after-commit half of a complete delete, shared by [deleteSong] and [replaceSong].
     *
     * Extracted rather than having [replaceSong] call [deleteSong]: that one opens its own
     * `database.withTransaction`, and [replaceSong]'s membership transplant plus row delete have
     * to be a single atomic unit — so the transaction-scoped part ([deleteSongRowWithArtRepoint])
     * and this out-of-transaction part are called separately by each caller, which also keeps the
     * backup-before-permit ordering from the deadlock fix intact on both paths.
     *
     * **Must be called with the deleting transaction already committed, from inside the
     * [dbOperation] permit.** Order within it is deliberate: live playback is told first so it can
     * skip off the song before its file disappears underneath the player, then the files go, then
     * the destructive-action counter records that a backup is now stale.
     */
    private suspend fun finishSongDeletion(id: String, filePath: String?): SongDeletionOutcome {
        SongDeletionEvents.notifyDeleted(id)
        val fileOutcome = filePath?.let { deleteSongAudioFile(context, it) }
            ?: AudioFileDeletion.NotFound
        deleteSongArtFile(context, id)
        DestructiveActionTracker.recordDestructiveAction(context)
        return if (fileOutcome == AudioFileDeletion.Failed) {
            SongDeletionOutcome.FileRemained
        } else {
            SongDeletionOutcome.Deleted
        }
    }

    override suspend fun previewReplaceSong(
        oldSongId: String,
        newSongId: String,
    ): ReplaceSongPreview? = dbOperation { withContext(Dispatchers.IO) {
        // One read of both songs, hydrated the same way every other song list is, so the review
        // step renders them with the ordinary SongRow/SongIdentity treatment.
        val details = songDao.getSongsWithDetailsByIds(listOf(oldSongId, newSongId))
        val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
            .groupBy { it.songId }
        val songsById = details.associate { it.song.id to it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        val oldSong = songsById[oldSongId] ?: return@withContext null
        val newSong = songsById[newSongId] ?: return@withContext null

        val oldPlaylistIds = playlistDao.getPlaylistIdsForSong(oldSongId).toSet()
        val newPlaylistIds = playlistDao.getPlaylistIdsForSong(newSongId).toSet()
        val namesById = playlistDao.getPlaylistsByIds((oldPlaylistIds + newPlaylistIds).toList())
            .associate { it.id to it.name }
        // A membership whose playlist row can't be named is skipped rather than shown as blank —
        // it cannot happen (playlist_songs has an FK to playlists) but the review step must not
        // present an unnamed line item as something the user is about to act on.
        fun names(ids: Set<String>) = ids.mapNotNull(namesById::get).sorted()

        ReplaceSongPreview(
            oldSong = oldSong,
            newSong = newSong,
            transferringPlaylists = names(oldPlaylistIds - newPlaylistIds),
            conflictingPlaylists = names(oldPlaylistIds intersect newPlaylistIds),
            newSongOnlyPlaylists = names(newPlaylistIds - oldPlaylistIds),
        )
    } }

    /**
     * See [SongRepository.replaceSong]. Structure mirrors [deleteSong] — it ends in one, after
     * all — including the backup-before-permit ordering that avoids the self-deadlock a backup
     * taken inside an operation permit causes.
     *
     * Everything that must be atomic (the membership transplant, the starred/date_added writes,
     * and the old row's deletion) is in the single transaction below. Everything that cannot be
     * rolled back (files, the live-playback notice) is in [finishSongDeletion] afterwards.
     */
    override suspend fun replaceSong(
        oldSongId: String,
        newSongId: String,
        conflictResolution: PlaylistConflictResolution,
    ): ReplaceSongResult = withContext(Dispatchers.IO) {
        // Cheapest guardrail first, and the one the UI already prevents by excluding the old song
        // from the new-song picker — checked here anyway so the contract holds for any caller.
        if (oldSongId == newSongId) return@withContext ReplaceSongResult.SameSong

        backupManager.createBackup(BackupTier.Auto, BackupReason.REPLACE_SONG)

        dbOperation {
            val transplant = database.withTransaction {
                transplantIdentity(oldSongId, newSongId, conflictResolution)
            }
            when (transplant) {
                is Transplant.OldMissing -> ReplaceSongResult.OldSongMissing
                is Transplant.NewMissing -> ReplaceSongResult.NewSongMissing
                is Transplant.Done -> {
                    val outcome = finishSongDeletion(oldSongId, transplant.oldFilePath)
                    playlistExportManager.scheduleExport()
                    ReplaceSongResult.Success(
                        playlistsAffected = transplant.playlistsAffected,
                        oldAudioFileRemoved = outcome == SongDeletionOutcome.Deleted,
                    )
                }
            }
        }
    }

    /** [transplantIdentity]'s result, carrying what the out-of-transaction half still needs. */
    private sealed interface Transplant {
        data class Done(val oldFilePath: String, val playlistsAffected: Int) : Transplant
        data object OldMissing : Transplant
        data object NewMissing : Transplant
    }

    /**
     * The whole atomic body of [replaceSong]. **Caller must already be inside a transaction** —
     * same contract as [deleteSongRowWithArtRepoint], which this ends by calling.
     */
    private suspend fun transplantIdentity(
        oldSongId: String,
        newSongId: String,
        conflictResolution: PlaylistConflictResolution,
    ): Transplant {
        val oldSong = songDao.getSongById(oldSongId) ?: return Transplant.OldMissing
        songDao.getSongById(newSongId) ?: return Transplant.NewMissing

        val oldMemberships = playlistDao.getPlaylistMembershipsForSong(oldSongId)
        val newMembershipPlaylistIds = playlistDao.getPlaylistMembershipsForSong(newSongId)
            .mapTo(HashSet()) { it.playlistId }

        for (membership in oldMemberships) {
            val isConflict = membership.playlistId in newMembershipPlaylistIds
            // The old song's row goes either way — it is deleted outright below, and FK CASCADE
            // would take it regardless. Removed explicitly and first so the row is gone before
            // the new one is written, rather than relying on cascade ordering.
            playlistDao.removePlaylistSong(membership.playlistId, oldSongId)

            // KeepNew is the only case where the new song stays where it already was; every
            // other case (no conflict at all, KeepOld, Merge) puts it in the old song's exact
            // slot. On a conflict that insert lands on an existing (playlist_id, song_id) row,
            // which insertPlaylistSong's REPLACE-on-conflict turns into a move rather than a
            // duplicate — see its kdoc.
            if (isConflict && conflictResolution == PlaylistConflictResolution.KeepNew) continue

            playlistDao.insertPlaylistSong(
                PlaylistSongEntity(
                    playlistId = membership.playlistId,
                    songId = newSongId,
                    position = membership.position,
                    // Inherited, not stamped as now: the replacement steps into the old song's
                    // membership wholesale, the same way it inherits date_added below.
                    addedAt = membership.addedAt,
                )
            )
        }

        // date_added before the starred rule, and both before the old row is deleted, purely so
        // this reads in the order the feature is specified.
        songDao.updateDateAdded(newSongId, oldSong.dateAdded)

        // Starred means "not yet organized into a playlist" (see SongEntity.isStarred), so a song
        // that now belongs to at least one is by definition organized. Asked of the database
        // after the transplant rather than computed from oldMemberships: on KeepNew with no
        // transferring playlists the new song may already have been a member of something the old
        // song never touched, and that still counts. Deliberately one-way — a new song that ends
        // up in no playlist keeps its own value untouched, and the old song's is never copied.
        if (songDao.isInAnyPlaylist(newSongId)) {
            songDao.dismissFromStarred(newSongId)
        }

        deleteSongRowWithArtRepoint(songDao, albumDao, oldSongId)
        return Transplant.Done(
            oldFilePath = oldSong.filePath,
            playlistsAffected = oldMemberships.size,
        )
    }

    // Sort order (date_added DESC) and the starred/missing filters stay in the DAO query;
    // this only resolves artist order, same as the playlist/album/artist song lists above.
    override fun getStarredSongs(): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getStarredSongs() }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    override fun getStarredSongCount(): Flow<Int> =
        RepositoryDbFlow.observe { songDao.getStarredSongCount() }

    // Sort order (date_added DESC) and the limit/missing filter stay in the DAO query;
    // this only resolves artist order, same as the other scoped song lists above.
    override fun getRecentSongs(limit: Int): Flow<List<Song>> =
        RepositoryDbFlow.observe {
            songDao.getRecentSongsWithDetails(limit)
        }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    // No backupManager.createBackup here, unlike deleteSong/deletePlaylist: this clears one
    // boolean and destroys nothing, so there is nothing to restore from.
    override suspend fun dismissFromStarred(songId: String) = dbOperation {
        songDao.dismissFromStarred(songId)
    }

    override suspend fun isInAnyPlaylist(songId: String): Boolean = dbOperation {
        songDao.isInAnyPlaylist(songId)
    }

    // No dbOperation{} wrapper here — graduateSongFile does its own file I/O under
    // Dispatchers.IO first and only gates its DB write through RepositoryDbOperation.run
    // internally, per this project's backup/DB admission conventions.
    //
    // channelFlow rather than plain flow{}: graduateSongFile's onStage callback below fires
    // from inside its own nested withContext(Dispatchers.IO) block — a different coroutine
    // from this builder's body, even though both land on the same dispatcher. flow{}'s
    // SafeCollector requires emit() to be called from the exact same coroutine the builder
    // block is running in, which a callback invoked from a nested withContext can't satisfy
    // (flowOn only reconciles the *dispatcher*, not this same-coroutine requirement).
    // channelFlow backs the flow with a channel instead, so send/trySend from any coroutine
    // is safe. trySend (not send) because stages are infrequent, discrete events with no
    // real backpressure concern — see the ChannelResult handling below for why a dropped
    // send still can't lose the terminal stage.
    //
    // flowOn(Dispatchers.IO) is kept: it still controls the dispatcher channelFlow's own
    // producer coroutine (and therefore graduateSongFile) runs on, same as with flow{} —
    // channelFlow doesn't make it redundant, it just no longer needs to reconcile the
    // same-coroutine constraint too.
    override fun graduateSong(songId: String, rootMusicFolderUri: Uri): Flow<GraduationStage> = channelFlow {
        graduateSongFile(context, songDao, songId, rootMusicFolderUri) { stage ->
            val result = trySend(stage)
            if (result.isFailure) {
                // Only reachable if this channel is already closed/cancelled (e.g. the
                // collector went away) — channelFlow closes it automatically once this
                // block returns, so a live graduation in progress always has an open
                // channel to send into. Logged rather than silently dropped in case that
                // assumption ever stops holding; not escalated to a thrown exception since
                // there is nothing a cancelled collector needs delivered to it anyway.
                Log.w(TAG, "Dropped graduation stage $stage — collector already gone")
            }
        }
    }.flowOn(Dispatchers.IO)

    // Debug tooling, read-only — no backupManager.createBackup here, unlike deleteSong: this
    // pass never writes to the DB.
    override suspend fun checkRootLibraryOrganization(musicFolderUri: Uri): RootOrganizationCheckResult? =
        dbOperation {
            walkAndClassifyRootOrganization(context, songDao, musicFolderUri)
        }

    override fun getMissingSongs(): Flow<List<MissingSongInfo>> =
        RepositoryDbFlow.observe { songDao.getMissingSongs() }

    override fun getMissingSongCount(): Flow<Int> =
        RepositoryDbFlow.observe { songDao.getMissingSongCount() }

    // Read-only filesystem check, same dbOperation gating as checkRootLibraryOrganization above
    // even though it writes nothing — RestoreCoordinator still needs to know this work is in
    // flight so a restore can drain it before touching filesDir/art/ or the songs table.
    override suspend fun findMissingArtSongIds(): List<String> = dbOperation {
        findMissingArtSongIdsAlreadyAdmitted()
    }

    /** [findMissingArtSongIds] for callers already holding admission — see [verifyLibrary] for
     *  the same split, used by the workers that go through [WorkerDatabaseAccess]. */
    internal suspend fun findMissingArtSongIdsAlreadyAdmitted(): List<String> =
        ArtRecovery.findMissingArt(context, songDao.getAllSongsOnce()).map { it.id }

    /** One-shot missing-song count for [WorkerDatabaseAccess]-admitted callers. The Flow-based
     *  [getMissingSongCount] above is for screens that need to keep observing; a worker doesn't. */
    internal suspend fun getMissingSongCountAlreadyAdmitted(): Int = songDao.getMissingSongCountOnce()

    override suspend fun recoverMissingArt(songIds: List<String>?): ArtRecoveryResult = dbOperation {
        ArtRecovery.recover(context, songDao, songIds)
    }

    /** Both cache values describe the same database snapshot. Caller owns worker admission. */
    internal suspend fun getMissingPaletteSnapshotAlreadyAdmitted(): Pair<List<String>, Int> =
        database.withTransaction {
            songDao.getSongIdsMissingPalette() to songDao.getMissingPaletteCountOnce()
        }

    override suspend fun recoverPaletteData(songIds: List<String>): PaletteRecoveryResult = dbOperation {
        PaletteRecovery.recover(context, database, songIds)
    }

    override fun getAllGroups(): Flow<List<PlaylistGroup>> =
        RepositoryDbFlow.observe { playlistDao.getAllGroups() }.map { entities ->
            entities.map { PlaylistGroup(id = it.id, name = it.name) }
        }

    override suspend fun createGroup(name: String) = dbOperation {
        playlistDao.insertGroup(
            PlaylistGroupEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                position = 0,
                createdAt = System.currentTimeMillis(),
            )
        )
        playlistExportManager.scheduleExport()
    }

    override suspend fun renameGroup(id: String, name: String) = dbOperation {
        playlistDao.renameGroup(id, name)
        playlistExportManager.scheduleExport()
    }

    override suspend fun deleteGroup(id: String) = dbOperation {
        playlistDao.deleteGroup(id)
        playlistExportManager.scheduleExport()
    }

    // Same caching rationale as songsFlow above — shared across every getAllPlaylists()
    // subscriber (SongActionsHost, Search) instead of re-querying per collector.
    // See songsFlow above for why the .catch must sit here, upstream of .stateIn.
    private var lastKnownPlaylists: List<Playlist> = emptyList()
    private val allPlaylistsFlow: StateFlow<List<Playlist>> =
        RepositoryDbFlow.observe { playlistDao.getAllPlaylists() }.map { entities ->
            entities.map { it.toDomain() }.also { lastKnownPlaylists = it }
        }.catch { e ->
            Log.e(TAG, "allPlaylistsFlow upstream failure — degrading to last known playlists", e)
            emit(lastKnownPlaylists)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getAllPlaylists(): Flow<List<Playlist>> = allPlaylistsFlow

    override fun getUngroupedPlaylists(): Flow<List<Playlist>> =
        RepositoryDbFlow.observe { playlistDao.getUngroupedPlaylists() }.map { entities ->
            entities.map { it.toDomain() }
        }

    override fun getPlaylistsForGroup(groupId: String): Flow<List<Playlist>> =
        RepositoryDbFlow.observe { playlistDao.getPlaylistsForGroup(groupId) }.map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun createPlaylist(name: String, groupId: String?): String = dbOperation {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        // The playlist's generated cover art, decided once here and never again (see
        // PlaylistEntity.artHue). Done on the creation path rather than left to
        // PlaylistArtBackfill so a user-created playlist already has its art the first time
        // the list redraws, with no placeholder flash.
        val hue = selectDistancedHue(playlistDao.getExistingArtHues())
        val structureSeed = Random.nextLong()
        playlistDao.insertPlaylist(
            PlaylistEntity(
                id = id,
                name = name,
                description = null,
                groupId = groupId,
                position = 0,
                createdAt = now,
                updatedAt = now,
                coverArtUri = null,
                artHue = hue,
                artStructureSeed = structureSeed,
            )
        )
        playlistExportManager.scheduleExport()
        id
    }

    override suspend fun renamePlaylist(id: String, name: String) = dbOperation {
        playlistDao.renamePlaylist(id, name, System.currentTimeMillis())
        playlistExportManager.scheduleExport()
    }

    override suspend fun movePlaylistToGroup(id: String, groupId: String?) = dbOperation {
        playlistDao.movePlaylistToGroup(id, groupId, System.currentTimeMillis())
        playlistExportManager.scheduleExport()
    }

    // Hooked here rather than in the ViewModels because both PlaylistListViewModel and
    // GroupDetailViewModel delete playlists through this one call.
    override suspend fun deletePlaylist(id: String) {
        // Backup waits for finite operations to drain, so it must precede our own permit.
        backupManager.createBackup(BackupTier.Auto, BackupReason.DELETE_PLAYLIST)
        // Recheck admission: restore may have begun while the backup was running.
        dbOperation {
            playlistDao.deletePlaylist(id)
            playlistExportManager.scheduleExport()
            DestructiveActionTracker.recordDestructiveAction(context)
        }
    }

    // Joins straight to playlist_songs (position order, is_missing filter) rather than
    // re-fetching/joining the whole library and filtering down in Kotlin.
    override fun getSongsForPlaylist(playlistId: String): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getSongsWithDetailsForPlaylist(playlistId) }.map { details ->
            val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

    override suspend fun getPlaylistIdsForSong(songId: String): List<String> = dbOperation {
        playlistDao.getPlaylistIdsForSong(songId)
    }

    override suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean = dbOperation {
        // P7.6/N9/6.6: contains-check, max-position read, and insert live in one
        // @Transaction DAO method so concurrent adds to the same playlist can't race on
        // the next position — see PlaylistDao.addSongToPlaylist.
        val added = playlistDao.addSongToPlaylist(playlistId, songId, System.currentTimeMillis())
        if (added) {
            playlistExportManager.scheduleExport()
        }
        added
    }

    override suspend fun removeSongFromPlaylist(playlistId: String, songId: String) = dbOperation {
        playlistDao.removePlaylistSong(playlistId, songId)
        playlistExportManager.scheduleExport()
    }

    override suspend fun getSongsByIds(ids: List<String>): List<Song> = dbOperation { withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val details = songDao.getSongsWithDetailsByIds(ids)
        val refsBySongId = songDao.getSongArtistRefsForSongs(details.map { it.song.id })
            .groupBy { it.songId }
        details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    } }

    override suspend fun getQueueSlots(): List<QueueSlot> = dbOperation { withContext(Dispatchers.IO) {
        resolveQueueSlots(queueDao.getQueueItems().map { QueueSlotRecord(it.slotId, it.songId) })
    } }

    override suspend fun getQueueSnapshot(): QueueSnapshot = dbOperation { withContext(Dispatchers.IO) {
        val (items, cursor) = queueDao.getQueueAndCursor()
        val records = items.map { QueueSlotRecord(it.slotId, it.songId) }
        QueueSnapshot(
            slots = resolveQueueSlots(records),
            storedSlotCount = records.size,
            cursor = cursor?.let {
                QueueCursor(
                    currentSlotId = it.currentSlotId,
                    positionMs = it.positionMs,
                    shuffleEnabled = it.shuffleEnabled,
                    repeatMode = it.repeatMode,
                )
            },
        )
    } }

    override suspend fun setQueueState(
        slots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
    ) = withContext(Dispatchers.IO) {
        queueDao.replaceQueueAndCursor(slots, currentSlotId, positionMs)
        // P6.3: the one place both sides are now guaranteed to have landed together —
        // a violation here means replaceQueueAndCursor's transaction itself is broken,
        // never a legitimate runtime state, so this must never silently pass in debug.
        if (BuildConfig.DEBUG) {
            check(currentSlotId == null || slots.any { it.slotId == currentSlotId }) {
                "Playback cursor invariant violated: currentSlotId=$currentSlotId is not in " +
                    "the ${slots.size} just-persisted queue slots"
            }
        }
    }

    override suspend fun setCursorAlreadyAdmitted(
        currentSlotId: String?,
        positionMs: Long,
    ) = withContext(Dispatchers.IO) {
        queueDao.updateCursor(currentSlotId, positionMs)
    }

    override suspend fun setShuffleEnabledAlreadyAdmitted(enabled: Boolean) = withContext(Dispatchers.IO) {
        queueDao.updateShuffleEnabled(enabled)
    }

    override suspend fun setRepeatModeAlreadyAdmitted(mode: Int) = withContext(Dispatchers.IO) {
        queueDao.updateRepeatMode(mode)
    }

    override suspend fun getBaselineQueueSlots(): List<QueueSlot> = dbOperation { withContext(Dispatchers.IO) {
        resolveQueueSlots(queueDao.getBaselineQueueItems().map { QueueSlotRecord(it.slotId, it.songId) })
    } }

    override suspend fun setBaselineQueueSlots(slots: List<QueueSlotRecord>) = withContext(Dispatchers.IO) {
        queueDao.replaceBaselineQueue(slots)
    }

    private suspend fun resolveQueueSlots(records: List<QueueSlotRecord>): List<QueueSlot> {
        if (records.isEmpty()) return emptyList()
        val songIds = records.map { it.songId }.distinct()
        // Room expands each IN-list element to one SQLite bind parameter. Queues can contain
        // well over 999 entries, so hydrate in bounded batches instead of failing restore for
        // a large queue with "too many SQL variables".
        val details = songIds.chunked(SQLITE_IN_BATCH_SIZE).flatMap {
            songDao.getSongsWithDetailsByIds(it)
        }
        val refsBySongId = details.map { it.song.id }.chunked(SQLITE_IN_BATCH_SIZE).flatMap {
            songDao.getSongArtistRefsForSongs(it)
        }
            .groupBy { it.songId }
        val songsById = details
            .asSequence()
            .filterNot { it.song.isMissing }
            .associate { it.song.id to it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        return records.mapNotNull { record ->
            songsById[record.songId]?.let { song -> QueueSlot(record.slotId, song) }
        }
    }

    private fun PlaylistEntity.toDomain(): Playlist =
        Playlist(
            id = id,
            name = name,
            groupId = groupId,
            albumArtUri = coverArtUri,
            artHue = artHue,
            artStructureSeed = artStructureSeed,
        )

    companion object {
        private const val SQLITE_IN_BATCH_SIZE = 900

        @Volatile private var INSTANCE: SongRepositoryImpl? = null

        // Manual DI entry point — everything hangs off the SynthXDatabase singleton. A true
        // singleton itself now (matching SynthXDatabase.getInstance()'s pattern) so getSongs()'s
        // cached StateFlow above is actually shared across every caller rather than rebuilt
        // per-caller.
        fun create(context: Context): SongRepositoryImpl {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val db = SynthXDatabase.getInstance(appContext)
                    val playlistExportManager = PlaylistExportManager.create(appContext)
                    SongRepositoryImpl(
                        context = appContext,
                        database = db,
                        songDao = db.songDao(),
                        artistDao = db.artistDao(),
                        albumDao = db.albumDao(),
                        playlistDao = db.playlistDao(),
                        queueDao = db.queueDao(),
                        folderScanner = FolderScanner(
                            context = appContext,
                            database = db,
                            songDao = db.songDao(),
                            artistDao = db.artistDao(),
                            albumDao = db.albumDao(),
                            songArtistDao = db.songArtistDao(),
                            playlistExportManager = playlistExportManager,
                        ),
                        backupManager = BackupManager.create(appContext),
                        playlistExportManager = playlistExportManager,
                    ).also { INSTANCE = it }
                }
            }
        }

        /** Stops long-lived Room flows before restore closes the singleton database. */
        suspend fun quiesceForRestore() {
            val repository = synchronized(this) {
                val current = INSTANCE
                INSTANCE = null
                current
            }
            repository?.repositoryJob?.cancelAndJoin()
        }

        internal fun resetForTest() {
            synchronized(this) { INSTANCE = null }
        }
    }
}
