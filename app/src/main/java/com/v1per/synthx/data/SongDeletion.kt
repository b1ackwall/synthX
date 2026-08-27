package com.v1per.synthx.data

import android.content.Context
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.SongDao
import java.io.File

/**
 * Re-points the song's album cover if this song was the one supplying it, then deletes the
 * song row. FK CASCADE already handles song_artists/playlist_songs/queue rows on its own;
 * this covers what a plain FK can't — the conditional album-art reassignment.
 *
 * Shared between [com.v1per.synthx.data.repository.SongRepositoryImpl.deleteSong] and
 * [com.v1per.synthx.data.scanner.FolderScanner]'s two internal evictions (path-collision
 * occupant, stale same-path/different-hash row) so both deletion paths do the same cleanup
 * instead of FolderScanner's bypassing it. Callers must already be inside a DB transaction —
 * this does not open its own.
 *
 * No-op if [songId] doesn't resolve to a row (already gone).
 */
suspend fun deleteSongRowWithArtRepoint(songDao: SongDao, albumDao: AlbumDao, songId: String) {
    val song = songDao.getSongById(songId) ?: return
    val albumId = song.albumId
    val artUri = song.albumArtUri

    // Only re-point when this song is the one the album borrowed its cover from;
    // reassigning unconditionally could replace good art with a null.
    if (albumId != null && artUri != null) {
        val album = albumDao.getAlbumById(albumId)
        if (album?.albumArtUri == artUri) {
            albumDao.updateAlbumArt(
                id = albumId,
                albumArtUri = songDao.findReplacementAlbumArt(albumId, songId),
            )
        }
    }
    songDao.deleteSongById(songId)
}

/**
 * Deletes a song's embedded-art files from filesDir/art/, if it has any. Best-effort (returns
 * false when the song never had embedded art, which is normal). Must only be called after the
 * transaction that deleted the song row has committed — a file delete cannot be rolled back
 * alongside the DB.
 *
 * Matches by songId prefix rather than a fixed extension: older art was written as
 * `art_<id>.png`, newer art as `art_<id>.jpg` (see FolderScanner.extractAlbumArt) — both must
 * be deletable without knowing which format a given song's file was written in. Also matches
 * the untouched-original full-resolution copy, `art_full_<id>.<ext>`, whose extension varies
 * by sniffed source format (jpg/png/webp).
 */
fun deleteSongArtFile(context: Context, songId: String): Boolean {
    val artDir = File(context.filesDir, "art")
    val matches = artDir.listFiles { file ->
        file.name.startsWith("art_$songId.") || file.name.startsWith("art_full_$songId.")
    } ?: return false
    return matches.fold(false) { deletedAny, file -> file.delete() || deletedAny }
}
