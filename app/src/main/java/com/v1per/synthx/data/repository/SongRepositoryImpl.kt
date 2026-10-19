package com.v1per.synthx.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.PlaylistExportManager
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.ArtistDao
import com.v1per.synthx.data.db.PendingCleanupDao
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.QueueDao
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.db.queryInBatches
import android.util.Log
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.deleteSongAudioFile
import com.v1per.synthx.data.scanner.ArtRecovery
import com.v1per.synthx.data.scanner.ArtRecoveryResult
import com.v1per.synthx.data.scanner.DestructiveFilePreflight
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.data.scanner.RootOrganizationCheckResult
import com.v1per.synthx.data.scanner.ScanResult
import com.v1per.synthx.data.scanner.ConfiguredAudioRoot
import com.v1per.synthx.data.scanner.VerifyResult
import com.v1per.synthx.data.scanner.walkAndClassifyRootOrganization
import com.v1per.synthx.data.repository.RepositoryDbFlow.retryOnFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val TAG = "SongRepositoryImpl"

class SongRepositoryImpl(
    internal val context: Context,
    internal val database: SynthXDatabase,
    internal val songDao: SongDao,
    private val artistDao: ArtistDao,
    internal val albumDao: AlbumDao,
    internal val playlistDao: PlaylistDao,
    internal val queueDao: QueueDao,
    internal val pendingCleanupDao: PendingCleanupDao,
    private val folderScanner: FolderScanner,
    internal val backupManager: BackupManager,
    internal val playlistExportManager: PlaylistExportManager,
    internal val destructiveFileCheck: suspend (String, String) -> DestructiveFilePreflightResult =
        DestructiveFilePreflight.create(context)::checkDestructive,
    internal val replacementFileCheck: suspend (String, String) -> ReplacementFilePreflightResult =
        DestructiveFilePreflight.create(context)::checkReplacement,
    internal val schedulePlaylistExport: () -> Unit = playlistExportManager::scheduleExport,
    internal val audioFileDelete: (String) -> AudioFileDeletion = { filePath ->
        deleteSongAudioFile(context, filePath)
    },
    // Deterministic transaction-boundary seam for the Room concurrency regression test.
    internal val previewAnalysisCheckpoint: suspend () -> Unit = {},
) : SongRepository,
    AdmittedQueuePersistenceRepository {

    internal suspend fun <T> dbOperation(block: suspend () -> T): T = RepositoryDbOperation.run(block)

    // This class is a singleton (see companion.create), so this scope effectively lives as
    // long as the app process does — same shape as ExoPlayerEngine.engineScope /
    // QueuePersistence.scope at their own instance-lifetime level.
    private val repositoryJob = SupervisorJob()
    internal val repositoryScope = CoroutineScope(repositoryJob + Dispatchers.Default)

    // Shared across every getSongs() subscriber instead of re-querying and re-mapping the
    // full library on every SongsViewModel instance. Lazily rather than
    // WhileSubscribed: local song data changes rarely and Songs is a frequently-revisited
    // screen, so once the query has run once it's worth keeping warm for the rest of the
    // process — instant navigation matters more here than releasing this one cache early.
    // Room's Flow still auto-invalidates on real table changes, so this stays correct
    // across scans/edits regardless of how long the cache is held.
    // Nothing upstream of this .stateIn can ever catch a failure
    // here — once the upstream Flow (including the extra getAllSongArtistRefs() query below)
    // throws, the stateIn producer coroutine dies and no downstream .catch{} on this StateFlow's
    // consumers can ever see it. .retryOnFailure resubscribes to the whole upstream chain (a
    // genuine re-acquire-permit + re-subscribe-to-DAO-flow retry, with exponential backoff) on any
    // non-cancellation failure, so a transient DAO error does not permanently freeze this
    // StateFlow. The .catch below is a last-resort backstop only reachable if retryOnFailure
    // itself is bypassed by a downstream cancellation — it logs and degrades to the last
    // successfully computed song list rather than crashing the process or freezing on an empty
    // list.
    private var lastKnownSongs: List<Song> = emptyList()
    private val songsFlow: StateFlow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getAllSongsWithDetails() }.map { entries ->
            // One refs query per emission — 2 queries total regardless of library size
            val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
            entries.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
                .also { lastKnownSongs = it }
        }.retryOnFailure(TAG, "songsFlow").catch { e ->
            Log.e(TAG, "songsFlow upstream failure — degrading to last known songs", e)
            emit(lastKnownSongs)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getSongs(): Flow<List<Song>> = songsFlow

    // Same caching rationale as songsFlow above — shared across every getArtists()
    // subscriber (Artists list, Search) instead of re-querying per collector.
    // See songsFlow above for why .retryOnFailure/.catch must sit here, upstream of .stateIn.
    private var lastKnownArtists: List<Artist> = emptyList()
    private val artistsFlow: StateFlow<List<Artist>> =
        RepositoryDbFlow.observe { artistDao.getAllArtists() }.map { entities ->
            entities.map {
                Artist(id = it.id, name = it.name, allSongsMissing = it.allSongsMissing)
            }.also { lastKnownArtists = it }
        }.retryOnFailure(TAG, "artistsFlow").catch { e ->
            Log.e(TAG, "artistsFlow upstream failure — degrading to last known artists", e)
            emit(lastKnownArtists)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getArtists(): Flow<List<Artist>> = artistsFlow

    override fun getSongsForArtist(artistId: String): Flow<List<Song>> = getSongsForArtistInternal(artistId)

    // Same caching rationale as songsFlow above — shared across every getAlbums()
    // subscriber (Albums list, Search) instead of re-querying per collector.
    // See songsFlow above for why .retryOnFailure/.catch must sit here, upstream of .stateIn.
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
        }.retryOnFailure(TAG, "albumsFlow").catch { e ->
            Log.e(TAG, "albumsFlow upstream failure — degrading to last known albums", e)
            emit(lastKnownAlbums)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getAlbums(): Flow<List<Album>> = albumsFlow

    override fun getSongsForAlbum(albumId: String): Flow<List<Song>> = getSongsForAlbumInternal(albumId)

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

    internal suspend fun verifyLibrary(configuredRoots: List<ConfiguredAudioRoot>): VerifyResult =
        folderScanner.verifyAlreadyAdmitted(configuredRoots)

    /**
     * Batched by-id fetch used purely for playback-cache reconciliation: a queue-loading
     * path that suspended across a cache-invalidating event (or the `onStart`/`onResume` recovery
     * pass) re-derives truth directly from the database with this rather than trusting a bus
     * event's payload. An id with no matching row (deleted, or never existed) is simply absent
     * from the result — the caller treats that the same way an explicit deletion notice would.
     */
    internal suspend fun getSongsByIdsForReconciliation(ids: List<String>): List<Song> = dbOperation {
        if (ids.isEmpty()) return@dbOperation emptyList()
        val details = ids.queryInBatches(songDao::getSongsWithDetailsByIds)
        val refsBySongId = details.map { it.song.id }
            .queryInBatches(songDao::getSongArtistRefsForSongs)
            .groupBy { it.songId }
        details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    }

    /** Total library size, for gauging a scan's impact as a percentage. */
    internal suspend fun getSongCountAlreadyAdmitted(): Int = songDao.getSongCount()

    override suspend fun deleteSong(id: String): SongDeletionOutcome = deleteSongInternal(id)

    override suspend fun previewDeleteSong(id: String): DeleteSongPreview = previewDeleteSongInternal(id)

    override suspend fun retryAudioFileDeletion(filePath: String): SongDeletionOutcome = withContext(Dispatchers.IO) {
        songDeletionOutcome(
            audioOutcome = audioFileDelete(filePath),
            cleanupUncertain = false,
        )
    }

    // See SongRepository.deleteSongDetached's kdoc. The whole call (locks, backup, transaction,
    // and the runPostCommitCleanup phase below) runs on repositoryScope from the start, so
    // IssuesViewModel's caller-side coroutine is never in the loop at all — cancelling it cannot
    // affect this in any way, not just the post-commit phase.
    override fun deleteSongDetached(id: String, onResult: (Result<SongDeletionOutcome>) -> Unit) {
        repositoryScope.launch {
            onResult(runCatching { deleteSong(id) })
        }
    }

    override suspend fun previewReplaceSong(oldSongId: String, newSongId: String): ReplaceSongPreviewResult =
        previewReplaceSongInternal(oldSongId, newSongId)

    override suspend fun replaceSong(
        oldSongId: String,
        newSongId: String,
        conflictResolution: PlaylistConflictResolution,
        expectedMemberships: ReplaceSongMembershipSnapshot,
    ): ReplaceSongResult = replaceSongInternal(oldSongId, newSongId, conflictResolution, expectedMemberships)

    override fun getStarredSongs(): Flow<List<Song>> = getStarredSongsInternal()

    override fun getStarredSongCount(): Flow<Int> = RepositoryDbFlow.observe { songDao.getStarredSongCount() }

    override fun getRecentSongs(limit: Int): Flow<List<Song>> = getRecentSongsInternal(limit)

    // No backupManager.createBackup here, unlike deleteSong/deletePlaylist: this clears one
    // boolean and destroys nothing, so there is nothing to restore from.
    override suspend fun dismissFromStarred(songId: String) = dbOperation {
        songDao.dismissFromStarred(songId)
    }

    override suspend fun isInAnyPlaylist(songId: String): Boolean = dbOperation {
        songDao.isInAnyPlaylist(songId)
    }

    override fun graduateSong(
        songId: String,
        rootMusicFolderUri: Uri,
        rootNewArrivalsFolderUri: Uri?,
    ): Flow<GraduationStage> = graduateSongInternal(songId, rootMusicFolderUri, rootNewArrivalsFolderUri)

    // Debug tooling, read-only — no backupManager.createBackup here, unlike deleteSong: this
    // pass never writes to the DB.
    override suspend fun checkRootLibraryOrganization(musicFolderUri: Uri): RootOrganizationCheckResult? = dbOperation {
        walkAndClassifyRootOrganization(context, songDao, musicFolderUri)
    }

    override fun getMissingSongs(): Flow<List<MissingSongInfo>> = RepositoryDbFlow.observe { songDao.getMissingSongs() }

    override fun getMissingSongCount(): Flow<Int> = RepositoryDbFlow.observe { songDao.getMissingSongCount() }

    /** [findMissingArtSongIds] for callers already holding admission — see [verifyLibrary] for
     *  the same split, used by the workers that go through [WorkerDatabaseAccess]. */
    internal suspend fun findMissingArtSongIdsAlreadyAdmitted(): List<String> =
        ArtRecovery.findMissingArt(context, songDao.getAllSongsOnce()).map { it.id }

    /** One-shot missing-song count for [WorkerDatabaseAccess]-admitted callers. The Flow-based
     *  [getMissingSongCount] above is for screens that need to keep observing; a worker doesn't. */
    internal suspend fun getMissingSongCountAlreadyAdmitted(): Int = songDao.getMissingSongCountOnce()

    override suspend fun recoverMissingArt(songIds: List<String>?): ArtRecoveryResult = dbOperation {
        ArtRecovery.recover(context, database, songIds)
    }

    override fun getAllGroups(): Flow<List<PlaylistGroup>> = getAllGroupsInternal()

    override suspend fun createGroup(name: String) = createGroupInternal(name)

    override suspend fun renameGroup(id: String, name: String) = renameGroupInternal(id, name)

    override suspend fun deleteGroup(id: String) = deleteGroupInternal(id)

    // Same caching rationale as songsFlow above — shared across every getAllPlaylists()
    // subscriber (SongActionsHost, Search) instead of re-querying per collector.
    // See songsFlow above for why .retryOnFailure/.catch must sit here, upstream of .stateIn.
    private var lastKnownPlaylists: List<Playlist> = emptyList()
    private val allPlaylistsFlow: StateFlow<List<Playlist>> =
        RepositoryDbFlow.observe { playlistDao.getAllPlaylists() }.map { entities ->
            entities.map { it.toDomain() }.also { lastKnownPlaylists = it }
        }.retryOnFailure(TAG, "allPlaylistsFlow").catch { e ->
            Log.e(TAG, "allPlaylistsFlow upstream failure — degrading to last known playlists", e)
            emit(lastKnownPlaylists)
        }.stateIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList(),
        )

    override fun getAllPlaylists(): Flow<List<Playlist>> = allPlaylistsFlow

    override fun getUngroupedPlaylists(): Flow<List<Playlist>> = getUngroupedPlaylistsInternal()

    override fun getPlaylistsForGroup(groupId: String): Flow<List<Playlist>> = getPlaylistsForGroupInternal(groupId)

    override suspend fun createPlaylist(name: String, groupId: String?): String = createPlaylistInternal(name, groupId)

    override suspend fun renamePlaylist(id: String, name: String) = renamePlaylistInternal(id, name)

    override suspend fun movePlaylistToGroup(id: String, groupId: String?) = movePlaylistToGroupInternal(id, groupId)

    override suspend fun deletePlaylist(id: String) = deletePlaylistInternal(id)

    override fun getSongsForPlaylist(playlistId: String): Flow<List<Song>> = getSongsForPlaylistInternal(playlistId)

    override suspend fun getPlaylistIdsForSong(songId: String): List<String> = getPlaylistIdsForSongInternal(songId)

    override suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean =
        addSongToPlaylistInternal(playlistId, songId)

    override suspend fun removeSongFromPlaylist(playlistId: String, songId: String) =
        removeSongFromPlaylistInternal(playlistId, songId)

    override suspend fun getSongsByIds(ids: List<String>): List<Song> = getSongsByIdsInternal(ids)

    override suspend fun getQueueSlots(): List<QueueSlot> = getQueueSlotsInternal()

    override suspend fun getQueueSnapshot(): QueueSnapshot = getQueueSnapshotInternal()

    override suspend fun setQueueState(slots: List<QueueSlotRecord>, currentSlotId: String?, positionMs: Long) =
        setQueueStateInternal(slots, currentSlotId, positionMs)

    override suspend fun setCursorAlreadyAdmitted(currentSlotId: String?, positionMs: Long) =
        setCursorAlreadyAdmittedInternal(currentSlotId, positionMs)

    override suspend fun setShuffleEnabledAlreadyAdmitted(enabled: Boolean) =
        setShuffleEnabledAlreadyAdmittedInternal(enabled)

    override suspend fun setRepeatModeAlreadyAdmitted(mode: Int) = setRepeatModeAlreadyAdmittedInternal(mode)

    override suspend fun getBaselineQueueSlots(): List<QueueSlot> = getBaselineQueueSlotsInternal()

    override suspend fun setBaselineQueueSlots(slots: List<QueueSlotRecord>) = setBaselineQueueSlotsInternal(slots)

    override suspend fun getQueueSnapshotWithBaseline(): Pair<QueueSnapshot, List<QueueSlot>> =
        getQueueSnapshotWithBaselineInternal()

    override suspend fun setUnifiedQueueState(
        activeSlots: List<QueueSlotRecord>,
        baselineSlots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
    ) = setUnifiedQueueStateInternal(
        activeSlots,
        baselineSlots,
        currentSlotId,
        positionMs,
        shuffleEnabled,
        repeatMode,
    )

    internal fun PlaylistEntity.toDomain(): Playlist = Playlist(
        id = id,
        name = name,
        groupId = groupId,
        albumArtUri = coverArtUri,
        artHue = artHue,
        artStructureSeed = artStructureSeed,
    )

    companion object {
        // Only applicationContext is stored here (see create).
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: SongRepositoryImpl? = null

        // Manual DI entry point — everything hangs off the SynthXDatabase singleton. A true
        // singleton itself now (matching SynthXDatabase.getInstance()'s pattern) so getSongs()'s
        // cached StateFlow above is actually shared across every caller rather than rebuilt
        // per-caller.
        fun create(context: Context): SongRepositoryImpl = INSTANCE ?: synchronized(this) {
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
                    pendingCleanupDao = db.pendingCleanupDao(),
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
