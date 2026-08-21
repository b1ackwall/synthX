package com.v1per.synthx.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.data.scanner.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Temporary feature — safe to delete. Removing this file plus its four other touch
 * points fully removes "Seed Playlists (Temporary)" with no other cleanup needed:
 *   1. This file (data/PlaylistSeeder.kt)
 *   2. ui/screens/librarymanagement/SeedPlaylistsSection.kt
 *   3. The `seedPlaylists()` function (plus `_seedPlaylistsStatus`/`isSeeding` state) in
 *      LibraryManagementViewModel.kt
 *   4. The `SeedPlaylistsSection(...)` call in LibraryManagementScreenContent, and its
 *      `seedPlaylistsStatus`/`isSeeding` params (LibraryManagementScreen.kt)
 *   5. The `seedPlaylistsStatus`/`isSeeding`/`onSeedPlaylists` args added to the three
 *      `LibraryManagementScreenContent(...)` preview call sites
 *      (LibraryManagementScreenPreviews.kt)
 *
 * One-shot tool for the "folders as playlists" seeding workflow: the selected music
 * folder's immediate subfolders are treated as playlists (folder name = playlist name,
 * contents = playlist members). Existing songs are matched back to their canonical
 * [com.v1per.synthx.data.db.SongEntity] by recomputing the exact same content hash
 * [HashUtils] uses, since duplicate physical copies of a song collapse to one DB row
 * during a normal library scan and file path alone is not a reliable match key.
 *
 * Fully idempotent — re-running always resyncs a playlist's membership to exactly what
 * its folder currently contains (via [SongRepository.setPlaylistSongs], a full
 * replace), so it can be re-run safely at any time without duplicating memberships.
 */

data class SeedResult(
    val playlistsCreated: Int,
    val playlistsUpdated: Int,
    val songsMatched: Int,
    val filesUnmatched: Int,
    val foldersSkipped: Int,
)

/**
 * Folder-level progress snapshot, reported once per subfolder finished (not per-file —
 * a typical playlist folder is tens of songs, so folder granularity already updates
 * multiple times a second on real libraries without the overhead/noise of a per-file
 * callback). [currentFolderName] is the folder just completed; [songsMatchedSoFar] is
 * the running total across all folders processed so far in this run.
 */
data class SeedProgress(
    val foldersProcessed: Int,
    val totalFolders: Int,
    val songsMatchedSoFar: Int,
    val currentFolderName: String,
)

private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg")

/**
 * Walks [musicFolderUri]'s immediate child directories, treating each as a playlist
 * candidate, and resyncs matching [SongRepository] playlists to their contents.
 * [onProgress] fires once per folder completed — see [SeedProgress].
 *
 * Deliberately duplicates [com.v1per.synthx.data.scanner.FolderScanner]'s private
 * audio-file-collection filtering (same extension set) rather than reusing it — that
 * logic isn't exposed, and this is intentionally kept out of the scanner entirely so
 * it stays isolated/removable. FolderScanner itself is not modified.
 *
 * Runs entirely on [Dispatchers.IO] — the whole function body is wrapped once rather
 * than wrapping individual calls, since every step here (SAF folder walk, per-file
 * hashing via [HashUtils.computeSongId], and the repository suspend calls) is I/O-bound
 * end to end; there's no CPU-bound or main-thread work in between to carve out.
 */
suspend fun seedPlaylistsFromFolders(
    context: Context,
    musicFolderUri: Uri,
    songRepository: SongRepository,
    onProgress: (SeedProgress) -> Unit = {},
): SeedResult = withContext(Dispatchers.IO) {
    val root = DocumentFile.fromTreeUri(context, musicFolderUri)
        ?: return@withContext SeedResult(0, 0, 0, 0, 0)

    val songsByHash = songRepository.getSongs().first().associateBy { it.id }

    var playlistsCreated = 0
    var playlistsUpdated = 0
    var songsMatched = 0
    var filesUnmatched = 0
    var foldersSkipped = 0

    // Hidden folders (dotfiles) are skipped — never meant to be playlists. A folder
    // whose contents match zero known songs is also skipped rather than creating an
    // empty/junk playlist; both cases are counted in foldersSkipped rather than failing
    // the whole run.
    val candidateFolders = root.listFiles().filter { it.isDirectory && it.name?.startsWith(".") == false }

    for ((index, folder) in candidateFolders.withIndex()) {
        val playlistName = folder.name ?: continue
        val audioFiles = collectAudioFilesRecursive(folder)

        val matchedSongIds = mutableListOf<String>()
        for (file in audioFiles) {
            val hash = HashUtils.computeSongId(file, context)
            val song = hash?.let { songsByHash[it] }
            if (song != null) {
                matchedSongIds.add(song.id)
            } else {
                filesUnmatched++
            }
        }

        if (matchedSongIds.isEmpty()) {
            foldersSkipped++
        } else {
            val existingPlaylist = songRepository.getAllPlaylists().first()
                .find { it.name == playlistName }

            val playlistId = if (existingPlaylist != null) {
                playlistsUpdated++
                existingPlaylist.id
            } else {
                playlistsCreated++
                songRepository.createPlaylist(playlistName, groupId = null)
            }

            songRepository.setPlaylistSongs(playlistId, matchedSongIds)
            songsMatched += matchedSongIds.size
        }

        onProgress(
            SeedProgress(
                foldersProcessed = index + 1,
                totalFolders = candidateFolders.size,
                songsMatchedSoFar = songsMatched,
                currentFolderName = playlistName,
            )
        )
    }

    SeedResult(
        playlistsCreated = playlistsCreated,
        playlistsUpdated = playlistsUpdated,
        songsMatched = songsMatched,
        filesUnmatched = filesUnmatched,
        foldersSkipped = foldersSkipped,
    )
}

private fun collectAudioFilesRecursive(dir: DocumentFile): List<DocumentFile> {
    val result = mutableListOf<DocumentFile>()
    for (child in dir.listFiles()) {
        when {
            child.isDirectory -> result.addAll(collectAudioFilesRecursive(child))
            child.name?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_EXTENSIONS ->
                result.add(child)
        }
    }
    return result
}
