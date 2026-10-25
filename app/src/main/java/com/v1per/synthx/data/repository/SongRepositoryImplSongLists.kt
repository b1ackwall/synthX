package com.v1per.synthx.data.repository

import android.net.Uri
import com.v1per.synthx.data.db.queryInBatches
import com.v1per.synthx.data.repository.RepositoryDbFlow.retryOnFailure
import android.util.Log
import com.v1per.synthx.data.scanner.GraduationStage
import com.v1per.synthx.data.scanner.graduateSong as graduateSongFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "SongRepositoryImpl"

// SongRepositoryImpl's hydrated song-list reads and graduation.

// Sort order (title) stays in the DAO query; the Flow conversion preserves it.
internal fun SongRepositoryImpl.getSongsForArtistInternal(artistId: String): Flow<List<Song>> =
    RepositoryDbFlow.observe { songDao.getSongsWithDetailsForArtist(artistId) }.map { details ->
        val refsBySongId = details.map { it.song.id }
            .queryInBatches(songDao::getSongArtistRefsForSongs)
            .groupBy { it.songId }
        details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    }.flowOn(Dispatchers.Default)

// Sort order (track number, then title) stays in the DAO query.
internal fun SongRepositoryImpl.getSongsForAlbumInternal(albumId: String): Flow<List<Song>> =
    RepositoryDbFlow.observe { songDao.getSongsWithDetailsForAlbum(albumId) }.map { details ->
        val refsBySongId = details.map { it.song.id }
            .queryInBatches(songDao::getSongArtistRefsForSongs)
            .groupBy { it.songId }
        details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    }.flowOn(Dispatchers.Default)

// Sort order (date_added DESC) and the starred filter stay in the DAO query;
// this only resolves artist order, same as the other scoped song lists in this file.
// A failed query or mapping is retried with backoff, so a collector keeps receiving updates
// after a transient error instead of ending with it.
internal fun SongRepositoryImpl.getStarredSongsInternal(): Flow<List<Song>> =
    RepositoryDbFlow.observe { songDao.getStarredSongs() }.map { details ->
        val refsBySongId = details.map { it.song.id }
            .queryInBatches(songDao::getSongArtistRefsForSongs)
            .groupBy { it.songId }
        details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    }.retryOnFailure(TAG, "starredFlow").flowOn(Dispatchers.Default)

// Sort order (date_added DESC) and the limit stay in the DAO query;
// this only resolves artist order, same as the other scoped song lists above.
internal fun SongRepositoryImpl.getRecentSongsInternal(limit: Int): Flow<List<Song>> = RepositoryDbFlow.observe {
    songDao.getRecentSongsWithDetails(limit)
}.map { details ->
    val refsBySongId = details.map { it.song.id }
        .queryInBatches(songDao::getSongArtistRefsForSongs)
        .groupBy { it.songId }
    details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
}.flowOn(Dispatchers.Default)

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
// channelFlow removes only the same-coroutine constraint, so it doesn't make flowOn
// redundant.
internal fun SongRepositoryImpl.graduateSongInternal(
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

// Joins straight to playlist_songs (position order) rather than
// re-fetching/joining the whole library and filtering down in Kotlin.
internal fun SongRepositoryImpl.getSongsForPlaylistInternal(playlistId: String): Flow<List<Song>> =
    RepositoryDbFlow.observe { songDao.getSongsWithDetailsForPlaylist(playlistId) }.map { details ->
        val refsBySongId = details.map { it.song.id }
            .queryInBatches(songDao::getSongArtistRefsForSongs)
            .groupBy { it.songId }
        details.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    }.flowOn(Dispatchers.Default)

internal suspend fun SongRepositoryImpl.getSongsByIdsInternal(ids: List<String>): List<Song> = dbOperation {
    withContext(Dispatchers.IO) {
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
    }
}
