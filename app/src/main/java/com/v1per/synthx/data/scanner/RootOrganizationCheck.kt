package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import com.v1per.synthx.data.db.SongDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One song found unorganized by [checkRootLibraryOrganization]: has a DB row, isn't flagged
 * missing, and belongs to no playlist.
 */
data class UnorganizedSong(val id: String, val title: String, val filePath: String)

/**
 * Result of a [checkRootLibraryOrganization] pass. Missing-flagged songs are excluded
 * entirely from both lists below — a song already known to be gone is a separate concern
 * (see [SongDao.getMissingSongs]), not an organization problem.
 */
data class RootOrganizationCheckResult(
    val unorganizedSongs: List<UnorganizedSong>,
    val unindexedFilePaths: List<String>,
) {
    val unorganizedCount: Int get() = unorganizedSongs.size
    val unindexedCount: Int get() = unindexedFilePaths.size
}

/**
 * Read-only debug audit of a single configured root (the main library root — never New
 * Arrivals; the caller decides which URI to pass). Live-walks [musicFolderUri] and
 * classifies every audio file found there against the DB, writing nothing:
 *
 * - No matching [SongDao.getSongByPath] row at all → "unindexed".
 * - A matching row flagged missing → excluded entirely from both categories.
 * - A matching row, not missing, with no playlist membership
 *   ([SongDao.isInAnyPlaylist] false) → "unorganized".
 * - A matching row, not missing, in at least one playlist → not of interest, skipped.
 *
 * Returns `null` if [musicFolderUri]'s root can't currently be resolved — revoked grant,
 * ejected volume, or any other reachability failure (see [isRootReachable]) — rather than
 * reporting a false "fully organized" result off an empty walk.
 *
 * Uses the same walk primitives ([isRootReachable], [collectAudioFiles]) and path
 * correlation (`file.uri.toString()` against `songs.file_path`) that [FolderScanner] itself
 * uses for its existing-row lookups.
 *
 * Named distinctly from [com.v1per.synthx.data.repository.SongRepository.checkRootLibraryOrganization]
 * (same-named member vs. top-level function would otherwise shadow each other at the call site).
 */
suspend fun walkAndClassifyRootOrganization(
    context: Context,
    songDao: SongDao,
    musicFolderUri: Uri,
): RootOrganizationCheckResult? = withContext(Dispatchers.IO) {
    val root = isRootReachable(context, musicFolderUri) ?: return@withContext null
    val walk = collectAudioFiles(context, root)
    if (!walk.complete) return@withContext null
    val audioFiles = walk.files

    val unorganized = mutableListOf<UnorganizedSong>()
    val unindexed = mutableListOf<String>()

    for (file in audioFiles) {
        currentCoroutineContext().ensureActive()
        val path = file.uri.toString()
        val song = songDao.getSongByPath(path)
        when {
            song == null -> unindexed.add(path)
            song.isMissing -> Unit
            !songDao.isInAnyPlaylist(song.id) ->
                unorganized.add(UnorganizedSong(id = song.id, title = song.title, filePath = song.filePath))
        }
    }

    RootOrganizationCheckResult(unorganizedSongs = unorganized, unindexedFilePaths = unindexed)
}
