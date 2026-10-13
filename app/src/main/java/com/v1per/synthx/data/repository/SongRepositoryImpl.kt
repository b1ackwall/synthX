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
import com.v1per.synthx.data.db.PendingCleanupDao
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.PlaylistEntity
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.QueueDao
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.db.queryInBatches
import android.util.Log
import androidx.room.withTransaction
import com.v1per.synthx.data.AudioFileDeletion
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.SongDeletionAcknowledgment
import com.v1per.synthx.data.SongDeletionEvents
import com.v1per.synthx.data.deleteSongArtFile
import com.v1per.synthx.data.deleteSongAudioFile
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import com.v1per.synthx.data.playlistart.selectDistancedHue
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
import com.v1per.synthx.data.scanner.graduateSong as graduateSongFile
import com.v1per.synthx.data.repository.RepositoryDbFlow.retryOnFailure
import com.v1per.synthx.preferences.DestructiveActionTracker
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val TAG = "SongRepositoryImpl"

/** How long [SongRepositoryImpl]'s deletion handoff barrier waits before proceeding anyway. */
private const val DELETION_HANDOFF_TIMEOUT_MS = 1_500L

internal suspend fun runPostCommitStep(
    step: String,
    logFailure: (String, Throwable) -> Unit = { failedStep, error ->
        Log.e(TAG, "Post-commit step failed: $failedStep", error)
    },
    block: suspend () -> Unit,
): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    try {
        logFailure(step, e)
    } catch (loggingCancellation: CancellationException) {
        throw loggingCancellation
    } catch (_: Throwable) {
        // Reporting a cleanup failure must not turn it back into a propagated post-commit failure.
    }
    false
}

internal fun songDeletionOutcome(
    audioOutcome: AudioFileDeletion,
    cleanupUncertain: Boolean,
): SongDeletionOutcome = when {
    audioOutcome == AudioFileDeletion.Failed -> SongDeletionOutcome.FileRemained
    audioOutcome == AudioFileDeletion.Unknown || cleanupUncertain ->
        SongDeletionOutcome.CleanupUncertain
    else -> SongDeletionOutcome.Deleted
}

internal fun includePlaylistExportOutcome(
    deletionOutcome: SongDeletionOutcome,
    exportSucceeded: Boolean,
): SongDeletionOutcome = if (!exportSucceeded && deletionOutcome == SongDeletionOutcome.Deleted) {
    SongDeletionOutcome.CleanupUncertain
} else {
    deletionOutcome
}

class SongRepositoryImpl(
    private val context: Context,
    private val database: SynthXDatabase,
    private val songDao: SongDao,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val playlistDao: PlaylistDao,
    private val queueDao: QueueDao,
    private val pendingCleanupDao: PendingCleanupDao,
    private val folderScanner: FolderScanner,
    private val backupManager: BackupManager,
    private val playlistExportManager: PlaylistExportManager,
    private val destructiveFileCheck: suspend (String, String) -> DestructiveFilePreflightResult =
        DestructiveFilePreflight.create(context)::checkDestructive,
    private val replacementFileCheck: suspend (String, String) -> ReplacementFilePreflightResult =
        DestructiveFilePreflight.create(context)::checkReplacement,
    private val schedulePlaylistExport: () -> Unit = playlistExportManager::scheduleExport,
    private val audioFileDelete: (String) -> AudioFileDeletion = { filePath ->
        deleteSongAudioFile(context, filePath)
    },
    // Deterministic transaction-boundary seam for the Room concurrency regression test.
    private val previewAnalysisCheckpoint: suspend () -> Unit = {},
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
    // general 7.3 / P1-T6 (R1-F10): nothing upstream of this .stateIn can ever catch a failure
    // here — once the upstream Flow (including the extra getAllSongArtistRefs() query below)
    // throws, the stateIn producer coroutine dies and no downstream .catch{} on this StateFlow's
    // consumers can ever see it. .retryOnFailure resubscribes to the whole upstream chain (a
    // genuine re-acquire-permit + re-subscribe-to-DAO-flow retry, with exponential backoff) on any
    // non-cancellation failure, so a transient DAO error no longer permanently freezes this
    // StateFlow. The .catch below is now a last-resort backstop only reachable if retryOnFailure
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

    // Sort order (title) stays in the DAO query; the Flow conversion preserves it.
    override fun getSongsForArtist(artistId: String): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getSongsWithDetailsForArtist(artistId) }.map { details ->
            val refsBySongId = details.map { it.song.id }
                .queryInBatches(songDao::getSongArtistRefsForSongs)
                .groupBy { it.songId }
            details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        }.flowOn(Dispatchers.Default)

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

    // Sort order (track number, then title) stays in the DAO query.
    override fun getSongsForAlbum(albumId: String): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getSongsWithDetailsForAlbum(albumId) }.map { details ->
            val refsBySongId = details.map { it.song.id }
                .queryInBatches(songDao::getSongArtistRefsForSongs)
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

    internal suspend fun verifyLibrary(configuredRoots: List<ConfiguredAudioRoot>): VerifyResult =
        folderScanner.verifyAlreadyAdmitted(configuredRoots)

    /**
     * Batched by-id fetch used purely for playback-cache reconciliation (P4-T2): a queue-loading
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

        // Song lock after the backup, never before — see FileOperationCoordinator's acquisition
        // order. Waits out an in-flight graduation of this exact song rather than deleting the
        // row out from under it, which used to leave its graduated copy orphaned for a scan to
        // re-import. Held through the file cleanup so a scan can't re-claim the row mid-delete.
        FileOperationCoordinator.withSongLock(id) {
            // Recheck admission: restore may have begun while the backup was running.
            dbOperation {
                // The permit is already held so restore cannot close Room while the current row is
                // read, but no transaction is opened until the live SAF preflight has passed (or
                // returned an intentionally non-blocking gone/ambiguous result).
                val beforeDelete = songDao.getSongById(id)
                if (beforeDelete != null) {
                    when (
                        val preflight = destructiveFileCheck(
                            beforeDelete.filePath,
                            beforeDelete.id,
                        )
                    ) {
                        is DestructiveFilePreflightResult.Blocking ->
                            return@dbOperation SongDeletionOutcome.Blocked(preflight)
                        DestructiveFilePreflightResult.Passed,
                        DestructiveFilePreflightResult.SourceGone,
                        DestructiveFilePreflightResult.Ambiguous -> Unit
                    }
                }
                val operationId = UUID.randomUUID().toString()
                val filePath = database.withTransaction {
                    val existing = songDao.getSongById(id)
                    deleteSongRowWithArtRepoint(songDao, albumDao, id)
                    // Durable BEFORE the row is gone (P3-T1 / R2-F09): same transaction as the
                    // row delete, so a crash right after commit still leaves a record behind for
                    // startup reconciliation to finish the file cleanup this call never got to.
                    pendingCleanupDao.insert(
                        PendingCleanupEntity(
                            operationId = operationId,
                            songId = id,
                            operationType = PendingCleanupType.DELETE.name,
                            filePath = existing?.filePath,
                            publishedPath = null,
                            phase = PendingCleanupPhase.Committed.name,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    existing?.filePath
                }
                val deletionOutcome = runPostCommitCleanup(operationId, id, filePath)
                // Known accepted limitation: if restore closes admission before the nested
                // pending-cleanup clear in runPostCommitCleanup, that clear throws and this
                // enqueue is not reached; the durable record remains for startup reconciliation.
                val exportSucceeded = runPostCommitStep("schedule playlist export") {
                    schedulePlaylistExport()
                }
                includePlaylistExportOutcome(deletionOutcome, exportSucceeded)
            }
        }
    }

    override suspend fun previewDeleteSong(id: String): DeleteSongPreview = dbOperation {
        database.withTransaction {
            val playlistIds = playlistDao.getPlaylistIdsForSong(id).distinct()
            if (playlistIds.isEmpty()) return@withTransaction DeleteSongPreview(emptyList())
            val namesById = playlistIds.queryInBatches(playlistDao::getPlaylistsByIds)
                .associate { it.id to it.name }
            val countsById = playlistIds.queryInBatches(playlistDao::getPlaylistMemberCounts)
                .associate { it.playlistId to it.memberCount }
            DeleteSongPreview(
                playlistIds.mapNotNull { playlistId ->
                    namesById[playlistId]?.let { name ->
                        DeleteSongPlaylistImpact(
                            name = name,
                            willBecomeEmpty = countsById[playlistId] == 1,
                        )
                    }
                }.sortedBy { it.name.lowercase() }
            )
        }
    }

    override suspend fun retryAudioFileDeletion(filePath: String): SongDeletionOutcome =
        withContext(Dispatchers.IO) {
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

    /**
     * Runs [finishSongDeletion] on [repositoryScope] instead of inline, and joins it — so a
     * caller whose own coroutine gets cancelled (P3-T1 / R4-F-D5, e.g. `IssuesViewModel`'s
     * `NavBackStackEntry` going away mid-delete) only loses the *wait*, not the cleanup itself:
     * [repositoryScope] is not a structured child of the caller's job, so cancelling the caller
     * cannot cancel work already launched on it. The normal (non-cancelled) case is unaffected —
     * this still returns the real [SongDeletionOutcome] once cleanup finishes.
     *
     * [operationId]'s [PendingCleanupEntity] is cleared once [finishSongDeletion] returns,
     * regardless of whether individual steps inside it failed — those ordinary failures are
     * already surfaced honestly via [SongDeletionOutcome] (`FileRemained`/`CleanupUncertain`),
     * which retrying via startup reconciliation would not change. The record exists to protect
     * against *interruption* (this function never returning at all, i.e. process death), not
     * against a provider that permanently refuses a delete — see the P3-T1 report for the full
     * reasoning.
     */
    private suspend fun runPostCommitCleanup(
        operationId: String,
        id: String,
        filePath: String?,
    ): SongDeletionOutcome {
        val cleanup = repositoryScope.async { finishSongDeletion(id, filePath) }
        val outcome = cleanup.await()
        dbOperation { pendingCleanupDao.clear(operationId) }
        return outcome
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
     * **Must be called with the deleting transaction already committed.** Order within it is
     * deliberate: live playback is told first so it can skip off the song before its file
     * disappears underneath the player, then the files go, then the destructive-action counter
     * records that a backup is now stale. Only ever called via [runPostCommitCleanup], which is
     * what actually protects this from being cut short by the caller's scope ending.
     */
    private suspend fun finishSongDeletion(id: String, filePath: String?): SongDeletionOutcome {
        var cleanupUncertain = false
        if (!runPostCommitStep("notify song deletion") { SongDeletionEvents.notifyDeleted(id) }) {
            cleanupUncertain = true
        }

        // Handoff barrier (P4-T4 / R3-F03): notifyDeleted's suspending emit only guarantees the
        // notice was enqueued into a buffered SharedFlow, not that any collector has run yet, let
        // alone that the resulting controller commands (skip/stop/remove) have reached the
        // player. Without this, the file delete right below can race a still-open handle. Bounded
        // rather than an unconditional await: with no live playback engine there is nothing to
        // hand off to (register() returns null), and even with one, an engine wedged for some
        // unrelated reason must not block a deletion the user explicitly asked for forever.
        val ack = runCatching { SongDeletionAcknowledgment.register(id) }.getOrNull()
        if (ack != null) {
            val acknowledged = withTimeoutOrNull(DELETION_HANDOFF_TIMEOUT_MS) { ack.await() }
            if (acknowledged == null) {
                Log.w(TAG, "Timed out waiting for playback to release song $id before deleting its file")
            }
        }

        var fileOutcome = if (filePath == null) {
            AudioFileDeletion.ConfirmedAbsent
        } else {
            AudioFileDeletion.Unknown
        }
        if (filePath != null && !runPostCommitStep("delete audio file") {
                fileOutcome = audioFileDelete(filePath)
            }
        ) {
            cleanupUncertain = true
        }

        if (!runPostCommitStep("delete cached song art") { deleteSongArtFile(context, id) }) {
            cleanupUncertain = true
        }
        if (!runPostCommitStep("record destructive action") {
                DestructiveActionTracker.recordDestructiveAction(context)
            }
        ) {
            cleanupUncertain = true
        }
        return songDeletionOutcome(fileOutcome, cleanupUncertain)
    }

    override suspend fun previewReplaceSong(
        oldSongId: String,
        newSongId: String,
    ): ReplaceSongPreviewResult = dbOperation { withContext(Dispatchers.IO) {
        database.withTransaction {
            analyzeReplaceSongInTransaction(oldSongId, newSongId)
        }
    } }

    /** Caller must be inside [SynthXDatabase.withTransaction]. */
    private suspend fun analyzeReplaceSongInTransaction(
        oldSongId: String,
        newSongId: String,
    ): ReplaceSongPreviewResult {
        // One read of both songs, hydrated the same way every other song list is, so the review
        // step renders them with the ordinary SongRow/SongIdentity treatment.
        val details = songDao.getSongsWithDetailsByIds(listOf(oldSongId, newSongId))
        val refsBySongId = details.map { it.song.id }
            .queryInBatches(songDao::getSongArtistRefsForSongs)
            .groupBy { it.songId }
        val songsById = details.associate { it.song.id to it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
        val oldSong = songsById[oldSongId] ?: return ReplaceSongPreviewResult.OldSongMissing
        val newSong = songsById[newSongId] ?: return ReplaceSongPreviewResult.NewSongMissing

        val oldMemberships = playlistDao.getPlaylistMembershipsForSong(oldSongId)
            .map { it.toReplaceFingerprint() }
            .sortedWith(replaceMembershipOrder)
        previewAnalysisCheckpoint()
        val newMemberships = playlistDao.getPlaylistMembershipsForSong(newSongId)
            .map { it.toReplaceFingerprint() }
            .sortedWith(replaceMembershipOrder)
        val oldPlaylistIds = oldMemberships.mapTo(linkedSetOf()) { it.playlistId }
        val newPlaylistIds = newMemberships.mapTo(linkedSetOf()) { it.playlistId }
        val namesById = (oldPlaylistIds + newPlaylistIds)
            .queryInBatches(playlistDao::getPlaylistsByIds)
            .associate { it.id to it.name }
        // A membership whose playlist row can't be named is skipped rather than shown as blank —
        // it cannot happen (playlist_songs has an FK to playlists) but the review step must not
        // present an unnamed line item as something the user is about to act on.
        fun names(ids: Set<String>) = ids.mapNotNull(namesById::get).sorted()

        return ReplaceSongPreviewResult.Ready(
            preview = ReplaceSongPreview(
                oldSong = oldSong,
                newSong = newSong,
                transferringPlaylists = names(oldPlaylistIds - newPlaylistIds),
                conflictingPlaylists = names(oldPlaylistIds intersect newPlaylistIds),
                newSongOnlyPlaylists = names(newPlaylistIds - oldPlaylistIds),
            ),
            membershipSnapshot = ReplaceSongMembershipSnapshot(oldMemberships, newMemberships),
        )
    }

    private fun PlaylistMembership.toReplaceFingerprint() = ReplaceSongMembershipFingerprint(
        playlistId = playlistId,
        position = position,
        addedAt = addedAt,
    )

    private val replaceMembershipOrder = compareBy<ReplaceSongMembershipFingerprint>(
        { it.playlistId },
        { it.position },
        { it.addedAt },
    )

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
        expectedMemberships: ReplaceSongMembershipSnapshot,
    ): ReplaceSongResult = withContext(Dispatchers.IO) {
        // Cheapest guardrail first, and the one the UI already prevents by excluding the old song
        // from the new-song picker — checked here anyway so the contract holds for any caller.
        if (oldSongId == newSongId) return@withContext ReplaceSongResult.SameSong

        backupManager.createBackup(BackupTier.Auto, BackupReason.REPLACE_SONG)

        // The old song is the one whose row and file this destroys, so it is the one locked — same
        // placement and reasoning as deleteSong. The new song only receives narrow column writes
        // inside the transaction, which graduation's own transactional re-read already tolerates.
        FileOperationCoordinator.withSongLock(oldSongId) { dbOperation {
            // Read under the restore permit and old-song lock, but before opening the transplant
            // transaction. Definite failures leave both rows and every membership untouched.
            val oldBeforeReplace = songDao.getSongById(oldSongId)
                ?: return@dbOperation ReplaceSongResult.OldSongMissing
            when (
                val preflight = destructiveFileCheck(
                    oldBeforeReplace.filePath,
                    oldBeforeReplace.id,
                )
            ) {
                is DestructiveFilePreflightResult.Blocking ->
                    return@dbOperation ReplaceSongResult.OldSongBlocked(preflight)
                DestructiveFilePreflightResult.Passed,
                DestructiveFilePreflightResult.SourceGone,
                DestructiveFilePreflightResult.Ambiguous -> Unit
            }

            val newBeforeReplace = songDao.getSongById(newSongId)
                ?: return@dbOperation ReplaceSongResult.NewSongMissing
            when (
                val preflight = replacementFileCheck(
                    newBeforeReplace.filePath,
                    newBeforeReplace.id,
                )
            ) {
                is ReplacementFilePreflightResult.Blocking ->
                    return@dbOperation ReplaceSongResult.NewSongUnusable(preflight)
                ReplacementFilePreflightResult.Passed,
                ReplacementFilePreflightResult.Ambiguous -> Unit
            }

            val transplant = database.withTransaction {
                transplantIdentity(oldSongId, newSongId, conflictResolution, expectedMemberships)
            }
            when (transplant) {
                is Transplant.OldMissing -> ReplaceSongResult.OldSongMissing
                is Transplant.NewMissing -> ReplaceSongResult.NewSongMissing
                is Transplant.StalePreview -> ReplaceSongResult.PreviewStale
                is Transplant.Done -> {
                    val deletionOutcome = runPostCommitCleanup(
                        transplant.operationId,
                        oldSongId,
                        transplant.oldFilePath,
                    )
                    val exportSucceeded = runPostCommitStep("schedule playlist export") {
                        schedulePlaylistExport()
                    }
                    ReplaceSongResult.Success(
                        oldAudioCleanup = includePlaylistExportOutcome(
                            deletionOutcome,
                            exportSucceeded,
                        ),
                    )
                }
            }
        } }
    }

    /** [transplantIdentity]'s result, carrying what the out-of-transaction half still needs. */
    private sealed interface Transplant {
        data class Done(val oldFilePath: String, val operationId: String) : Transplant
        data object OldMissing : Transplant
        data object NewMissing : Transplant
        data object StalePreview : Transplant
    }

    /**
     * The whole atomic body of [replaceSong]. **Caller must already be inside a transaction** —
     * same contract as [deleteSongRowWithArtRepoint], which this ends by calling.
     */
    private suspend fun transplantIdentity(
        oldSongId: String,
        newSongId: String,
        conflictResolution: PlaylistConflictResolution,
        expectedMemberships: ReplaceSongMembershipSnapshot,
    ): Transplant {
        val current = when (val analysis = analyzeReplaceSongInTransaction(oldSongId, newSongId)) {
            ReplaceSongPreviewResult.OldSongMissing -> return Transplant.OldMissing
            ReplaceSongPreviewResult.NewSongMissing -> return Transplant.NewMissing
            is ReplaceSongPreviewResult.Ready -> analysis
        }
        if (current.membershipSnapshot != expectedMemberships) return Transplant.StalePreview

        val oldSong = current.preview.oldSong
        val oldMemberships = current.membershipSnapshot.oldSongMemberships
        val newMembershipPlaylistIds = current.membershipSnapshot.newSongMemberships
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

        val operationId = UUID.randomUUID().toString()
        // Same durability discipline as deleteSong (P3-T1 / R2-F09 / R4-F-D4): written inside
        // this same transaction, before the old row is provably gone to any reader outside it.
        pendingCleanupDao.insert(
            PendingCleanupEntity(
                operationId = operationId,
                songId = oldSongId,
                operationType = PendingCleanupType.DELETE.name,
                filePath = oldSong.filePath,
                publishedPath = null,
                phase = PendingCleanupPhase.Committed.name,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return Transplant.Done(
            oldFilePath = oldSong.filePath,
            operationId = operationId,
        )
    }

    // Sort order (date_added DESC) and the starred/missing filters stay in the DAO query;
    // this only resolves artist order, same as the playlist/album/artist song lists above.
    override fun getStarredSongs(): Flow<List<Song>> =
        RepositoryDbFlow.observe { songDao.getStarredSongs() }.map { details ->
            val refsBySongId = details.map { it.song.id }
                .queryInBatches(songDao::getSongArtistRefsForSongs)
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
            val refsBySongId = details.map { it.song.id }
                .queryInBatches(songDao::getSongArtistRefsForSongs)
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

    // No dbOperation{} wrapper here — graduateSongFile takes its own song lock and one
    // RestoreCoordinator permit held for its whole duration (file I/O included), in the
    // acquisition order FileOperationCoordinator documents; wrapping it here would put the
    // permit before the song lock.
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
    override fun graduateSong(
        songId: String,
        rootMusicFolderUri: Uri,
        rootNewArrivalsFolderUri: Uri?,
    ): Flow<GraduationStage> = channelFlow {
        graduateSongFile(
            context,
            database,
            songDao,
            songId,
            rootMusicFolderUri,
            rootNewArrivalsFolderUri,
        ) { stage ->
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
        ArtRecovery.recover(context, database, songIds)
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
        val structureSeed = Random.nextLong()
        // P8-T1/R1-F07: reading the existing hues and inserting the new row must be one
        // transaction — otherwise two concurrent creators (or a creator overlapping a
        // PlaylistArtBackfill pass) can both read the same used-hue snapshot and pick the same
        // "unused" hue before either write lands. Room/SQLite serializes writers at the
        // transaction level, so this alone is enough; it needs no visibility into a concurrent
        // backfill pass's in-flight choices (see PlaylistArtBackfill's own mutex for that half).
        database.withTransaction {
            // The playlist's generated cover art, decided once here and never again (see
            // PlaylistEntity.artHue). Done on the creation path rather than left to
            // PlaylistArtBackfill so a user-created playlist already has its art the first time
            // the list redraws, with no placeholder flash.
            val hue = selectDistancedHue(playlistDao.getExistingArtHues())
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
        }
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
            val refsBySongId = details.map { it.song.id }
                .queryInBatches(songDao::getSongArtistRefsForSongs)
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
        val distinctIds = ids.distinct()
        val details = distinctIds.queryInBatches(songDao::getSongsWithDetailsByIds)
        val refsBySongId = details.map { it.song.id }
            .queryInBatches(songDao::getSongArtistRefsForSongs)
            .groupBy { it.songId }
        val songsById = details.associate { row ->
            row.song.id to row.toDomain(refsBySongId[row.song.id] ?: emptyList())
        }
        distinctIds.mapNotNull(songsById::get)
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

    override suspend fun getQueueSnapshotWithBaseline(): Pair<QueueSnapshot, List<QueueSlot>> =
        dbOperation { withContext(Dispatchers.IO) {
            val (items, baselineItems, cursor) = queueDao.getFullQueueSnapshot()
            val records = items.map { QueueSlotRecord(it.slotId, it.songId) }
            val baselineRecords = baselineItems.map { QueueSlotRecord(it.slotId, it.songId) }
            val snapshot = QueueSnapshot(
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
            snapshot to resolveQueueSlots(baselineRecords)
        } }

    override suspend fun setUnifiedQueueState(
        activeSlots: List<QueueSlotRecord>,
        baselineSlots: List<QueueSlotRecord>,
        currentSlotId: String?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
    ) = withContext(Dispatchers.IO) {
        queueDao.replaceQueueBaselineAndCursor(
            activeSlots, baselineSlots, currentSlotId, positionMs, shuffleEnabled, repeatMode,
        )
        if (BuildConfig.DEBUG) {
            check(currentSlotId == null || activeSlots.any { it.slotId == currentSlotId }) {
                "Playback cursor invariant violated: currentSlotId=$currentSlotId is not in " +
                    "the ${activeSlots.size} just-persisted active queue slots"
            }
        }
    }

    private suspend fun resolveQueueSlots(records: List<QueueSlotRecord>): List<QueueSlot> {
        if (records.isEmpty()) return emptyList()
        val songIds = records.map { it.songId }.distinct()
        // Room expands each IN-list element to one SQLite bind parameter. Queues can contain
        // well over 999 entries, so hydrate in bounded batches instead of failing restore for
        // a large queue with "too many SQL variables".
        val details = songIds.queryInBatches(songDao::getSongsWithDetailsByIds)
        val refsBySongId = details.map { it.song.id }
            .queryInBatches(songDao::getSongArtistRefsForSongs)
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
