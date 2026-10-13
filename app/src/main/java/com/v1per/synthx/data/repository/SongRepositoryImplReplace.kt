package com.v1per.synthx.data.repository

import com.v1per.synthx.data.backup.BackupReason
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.db.PendingCleanupEntity
import com.v1per.synthx.data.db.PendingCleanupPhase
import com.v1per.synthx.data.db.PendingCleanupType
import com.v1per.synthx.data.db.PlaylistSongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.db.queryInBatches
import androidx.room.withTransaction
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.deleteSongRowWithArtRepoint
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.ReplacementFilePreflightResult
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// SongRepositoryImpl's replace-song preview and identity transplant.

internal suspend fun SongRepositoryImpl.previewReplaceSongInternal(
    oldSongId: String,
    newSongId: String,
): ReplaceSongPreviewResult = dbOperation { withContext(Dispatchers.IO) {
    database.withTransaction {
        analyzeReplaceSongInTransaction(oldSongId, newSongId)
    }
} }

/** Caller must be inside [SynthXDatabase.withTransaction]. */
private suspend fun SongRepositoryImpl.analyzeReplaceSongInTransaction(
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
internal suspend fun SongRepositoryImpl.replaceSongInternal(
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
private suspend fun SongRepositoryImpl.transplantIdentity(
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
